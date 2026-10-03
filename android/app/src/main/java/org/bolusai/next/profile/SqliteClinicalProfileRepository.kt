package org.bolusai.next.profile

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.bolusai.profile.*
import java.io.Closeable
import java.util.UUID

/**
 * Device adapter for the clinical profile port (ADR 0012 and ADR 0014). Own database file, independent of meal drafts.
 * Versions and confirmation events are append-only: triggers abort UPDATE/DELETE and the shared policies decide every
 * insert inside one exclusive transaction, after re-validating versions and events. The schema is verified by
 * definition when the file is opened and migrated; every read re-derives fingerprints and fails closed.
 */
internal class SqliteClinicalProfileRepository(context: Context, name: String? = DATABASE_NAME) :
    ClosableProfileRepository {
    private class CorruptStorage : RuntimeException()

    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, SCHEMA_VERSION,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException(ProfileFailure.CORRUPT_STORAGE.code) }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL")
            db.setForeignKeyConstraintsEnabled(true)
        }

        /** Runs inside the helper's transaction: a failure leaves the file at user_version 0 with its objects untouched. */
        override fun onCreate(db: SQLiteDatabase) {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','trigger','index','view') AND " +
                "name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null).use {
                if (it.moveToFirst()) throw UnsupportedProfileSchema()
            }
            ClinicalProfileSchema.SCHEMA_V2.forEach(db::execSQL)
            ClinicalProfileSchema.verify(db, ClinicalProfileSchema.V2)
        }

        /**
         * v1 -> v2 inside the helper's transaction (ADR 0014, section 8.4). Any exception rolls back: the file stays at
         * v1 exactly as it was and the next start retries from the same state. No row is rewritten, repaired or deleted,
         * and no confirmation is created.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != ClinicalProfileSchema.V1 || newVersion != ClinicalProfileSchema.V2) throw UnsupportedProfileSchema()
            ClinicalProfileSchema.verify(db, ClinicalProfileSchema.V1)
            db.rawQuery("PRAGMA foreign_key_check", null).use { if (it.moveToFirst()) throw CorruptStorage() }
            db.rawQuery("PRAGMA quick_check", null).use {
                if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) throw CorruptStorage()
            }
            val versions = ClinicalProfileRows.readVersions(db)
            if (versions.isNotEmpty()) ProfileHistory.Loaded(versions.sortedByDescending { it.version })
            ClinicalProfileSchema.V2_ADDITIONS.forEach(db::execSQL)
            ClinicalProfileSchema.verify(db, ClinicalProfileSchema.V2)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw UnsupportedProfileSchema() }

        /** Every opening compares definitions with the frozen v2 text; a mismatch closes the file untouched. */
        override fun onOpen(db: SQLiteDatabase) { ClinicalProfileSchema.verify(db, ClinicalProfileSchema.V2) }
    }

    override fun readVersions(): ProfileRead = when (val read = readRecord()) {
        is ProfileRecordRead.Failed -> ProfileRead.Failed(read.reason)
        is ProfileRecordRead.Loaded -> ProfileRead.Loaded(
            (read.record.history as? ProfileHistory.Loaded)?.versions.orEmpty().sortedBy { it.version })
    }

    override fun readRecord(): ProfileRecordRead = try {
        val db = helper.readableDatabase
        var read: ProfileRecordRead = ProfileRecordRead.Failed(ProfileFailure.READ_FAILED)
        // One transaction keeps versions, segments and events consistent with each other.
        db.transaction { read = ClinicalProfileRows.readRecord(db) }
        read
    } catch (failure: RuntimeException) {
        ProfileRecordRead.Failed(reason(failure, ProfileFailure.READ_FAILED))
    }

    override fun save(write: ProfileWrite, createdAtEpochMs: Long, writer: String): ProfileSave = try {
        val db = helper.writableDatabase
        var result: ProfileSave = ProfileSave.Failed(ProfileFailure.SAVE_FAILED)
        db.transaction {
            // Never append to a history that cannot be proven: versions and confirmation events are re-validated
            // inside this transaction (ADR 0012 and ADR 0014, section 7.5) before the policy runs.
            when (val read = ClinicalProfileRows.readRecord(db)) {
                is ProfileRecordRead.Failed -> result = ProfileSave.Failed(read.reason)
                is ProfileRecordRead.Loaded -> {
                    val history = read.record.history as? ProfileHistory.Loaded
                    val latest = history?.latest
                    val atBasePlusOne = if (write.baseVersion >= 0 && write.baseVersion < Long.MAX_VALUE)
                        history?.version(write.baseVersion + 1) else null
                    val source = write.restoredFrom?.let { history?.version(it) }
                    result = when (val decision = ProfileWritePolicy.evaluate(write, createdAtEpochMs, writer, latest,
                        atBasePlusOne, source)) {
                        is ProfileWriteDecision.Reject -> ProfileSave.Failed(decision.reason)
                        is ProfileWriteDecision.Existing -> ProfileSave.Saved(decision.version)
                        is ProfileWriteDecision.Insert -> {
                            ClinicalProfileRows.insertVersion(db, decision.version)
                            // Re-read inside the transaction: what is committed is exactly what the policy approved.
                            check(ClinicalProfileRows.readVersion(db, decision.version.version) == decision.version)
                            ProfileSave.Saved(decision.version)
                        }
                    }
                }
            }
        }
        // Including commit failure: Saved is visible only after the transaction returns.
        result
    } catch (failure: RuntimeException) {
        ProfileSave.Failed(reason(failure, ProfileFailure.SAVE_FAILED))
    }

    override fun appendConfirmation(request: ConfirmationRequest, recordedAtEpochMs: Long, writer: String,
                                    zones: TimeZoneRules): ConfirmationWrite = try {
        val db = helper.writableDatabase
        var result: ConfirmationWrite = ConfirmationWrite.Failed(ProfileFailure.SAVE_FAILED)
        db.transaction {
            // Step 1 of ADR 0014, section 7.1: even an idempotent retry is answered only from a proven history.
            when (val read = ClinicalProfileRows.readRecord(db)) {
                is ProfileRecordRead.Failed -> result = ConfirmationWrite.Failed(read.reason)
                is ProfileRecordRead.Loaded -> result = when (val decision =
                    ConfirmationPolicy.evaluate(request, recordedAtEpochMs, writer, read.record, zones)) {
                    is ConfirmationDecision.Reject -> ConfirmationWrite.Failed(decision.reason)
                    is ConfirmationDecision.Replay -> ConfirmationWrite.Recorded(decision.event, true, read.record)
                    is ConfirmationDecision.Insert -> {
                        ClinicalProfileRows.insertEvent(db, decision.event)
                        // Re-read and re-validate inside the transaction before committing.
                        val committed = checkNotNull((ClinicalProfileRows.readRecord(db) as? ProfileRecordRead.Loaded)?.record)
                        check(committed.event(decision.event.seq) == decision.event)
                        ConfirmationWrite.Recorded(decision.event, false, committed)
                    }
                }
            }
        }
        // Recorded is visible only after the transaction, including its commit, returned.
        result
    } catch (failure: RuntimeException) {
        ConfirmationWrite.Failed(storageTrigger(failure) ?: reason(failure, ProfileFailure.SAVE_FAILED))
    }

    /** A storage trigger that fired despite the shared policy reports its own stable code. */
    private fun storageTrigger(failure: RuntimeException): ProfileFailure? = (failure as? SQLiteConstraintException)?.message
        ?.let { message -> ProfileFailure.entries.firstOrNull { it.name.startsWith("CONFIRMATION_") && message.contains(it.code) } }

    private fun reason(failure: RuntimeException, fallback: ProfileFailure): ProfileFailure = when (failure) {
        is UnsupportedProfileSchema, is ClinicalProfileSchema.Mismatch -> ProfileFailure.UNSUPPORTED_SCHEMA
        is CorruptStorage, is SQLiteDatabaseCorruptException -> ProfileFailure.CORRUPT_STORAGE
        is IllegalArgumentException, is ArithmeticException, is NoSuchElementException -> ProfileFailure.INVALID_RECORD
        else -> fallback
    }

    override fun close() = helper.close()

    internal companion object {
        const val DATABASE_NAME = "clinical-profile.db"
        const val SCHEMA_VERSION = ClinicalProfileSchema.V2
    }
}

/** The profile port plus the lifecycle the screen owns; lets tests wrap the SQLite adapter without another store. */
internal interface ClosableProfileRepository : ClinicalProfileRepository, Closeable

/** Time zone existence from the device tz database; region identifiers only, no fixed offsets. */
internal object AndroidTimeZoneRules : TimeZoneRules {
    override fun exists(id: String): Boolean = id in java.time.ZoneId.getAvailableZoneIds()
}

/** Operation identities for confirmations and revocations: random UUIDs in canonical lowercase form. */
internal object AndroidOperationIds : OperationIds {
    override fun next(): OperationId = OperationId(UUID.randomUUID().toString())
}
