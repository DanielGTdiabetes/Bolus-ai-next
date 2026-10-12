package org.bolusai.next.glucose.dexcom.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.bolusai.dexcomg7.ApprovedPermissionSet
import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.DexcomG7PayloadRejection
import org.bolusai.dexcomg7.DexcomG7RejectionCode
import org.bolusai.dexcomg7.DexcomObservationStore
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.dexcomg7.ObservationLatest
import org.bolusai.dexcomg7.ObservationLatestRead
import org.bolusai.dexcomg7.ObservationPolicy
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.dexcomg7.ObservationSummaryRead
import org.bolusai.dexcomg7.ObservationWrite
import org.bolusai.dexcomg7.StoredMessage
import org.bolusai.dexcomg7.StoredMessageOutcome
import org.bolusai.dexcomg7.StoredReading
import org.bolusai.dexcomg7.TransmitterFingerprint
import org.bolusai.dexcomg7.TransmitterHasher
import org.bolusai.next.glucose.dexcom.DexcomReceptionBlocks
import org.bolusai.next.glucose.dexcom.GateCause
import java.io.Closeable

/**
 * Device adapter of the Dexcom observation log (ADR 0020; schema v2 and durable reception blocks, ADR 0023). Own database file, append-only by triggers, schema verified
 * by definition on every opening. The policy decides each append inside one exclusive transaction and the result is
 * re-read before commit. Every read decodes strictly and fails closed. No row is ever updated or deleted.
 */
internal class SqliteDexcomObservationStore(
    context: Context,
    name: String? = DATABASE_NAME,
    private val ids: ObservationIds,
    private val hasher: TransmitterHasher,
) : DexcomObservationStore, DexcomReceptionBlocks, Closeable {
    private class CorruptRow : RuntimeException("dexcom_observation.corrupt_storage")

    // Before SQLite sees the name: during instrumentation the app's own file is never opened (DexcomStorageGuard).
    init { DexcomStorageGuard.checkDatabase(context.applicationContext, name) }

    private val file = name?.let { context.applicationContext.getDatabasePath(it) }

    /** Never the platform default, which deletes a file it considers corrupt. */
    private val errorHandler = DatabaseErrorHandler { throw SQLiteDatabaseCorruptException(ObservationFailure.CORRUPT_STORAGE.code) }

    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, DexcomObservationSchema.V2,
        errorHandler) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL")
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','trigger','index','view') AND " +
                "name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null).use {
                if (it.moveToFirst()) throw DexcomObservationSchema.Mismatch("foreign objects")
            }
            DexcomObservationSchema.SCHEMA_V2.forEach(db::execSQL)
            DexcomObservationSchema.verify(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != DexcomObservationSchema.V1 || newVersion != DexcomObservationSchema.V2) {
                throw DexcomObservationSchema.Mismatch("upgrade $oldVersion")
            }
            DexcomObservationSchema.upgradeEmptyV1(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw DexcomObservationSchema.Mismatch("downgrade $oldVersion")
        }

        override fun onOpen(db: SQLiteDatabase) { DexcomObservationSchema.verify(db) }
    }

    override fun record(input: ObservationInput): ObservationWrite = try {
        val db = helper.writableDatabase
        var result: ObservationWrite = ObservationWrite.Failed(ObservationFailure.WRITE_FAILED)
        db.transaction {
            val plan = ObservationPolicy.plan(input, ids, hasher) { transmitter, timestamp ->
                readReadings(db, "transmitter_sha256 = ? AND producer_timestamp = ?",
                    arrayOf(transmitter.sha256Hex, timestamp.toString()))
            }
            insertMessage(db, plan.message)
            plan.readings.forEach { insertReading(db, it) }
            // What is committed is exactly what the policy decided.
            check(readMessages(db, "message_id = ?", arrayOf(plan.message.messageId)) == listOf(plan.message))
            check(readReadings(db, "message_id = ?", arrayOf(plan.message.messageId)) == plan.readings)
            result = ObservationWrite.Recorded(plan.message, plan.readings)
        }
        // Including commit failure: Recorded is visible only after the transaction returned.
        result
    } catch (failure: RuntimeException) {
        ObservationWrite.Failed(reason(failure, ObservationFailure.WRITE_FAILED))
    }

    override fun read(): ObservationRead = try {
        val db = helper.readableDatabase
        var read: ObservationRead = ObservationRead.Failed(ObservationFailure.READ_FAILED)
        db.transaction { read = ObservationRead.Loaded(readMessages(db, null, null), readReadings(db, null, null)) }
        read
    } catch (failure: RuntimeException) {
        ObservationRead.Failed(reason(failure, ObservationFailure.READ_FAILED))
    }

    /**
     * Aggregates in SQL inside one read transaction (ADR 0021, F5): the history is never loaded. Only the last message
     * is decoded, strictly. Unknown stored rejection codes are corruption, as in [read].
     */
    override fun summary(): ObservationSummaryRead = try {
        val db = helper.readableDatabase
        var result: ObservationSummaryRead = ObservationSummaryRead.Failed(ObservationFailure.READ_FAILED)
        db.transaction {
            val totals = db.rawQuery("SELECT COUNT(*), TOTAL(outcome = 'observed'), TOTAL(outcome = 'payload_unreadable'), " +
                "TOTAL(duplicate_readings), TOTAL(collapsed_in_message) FROM dexcom_messages", null).use { c ->
                check(c.moveToFirst())
                List(5) { c.getLong(it) }
            }
            val rejected = db.rawQuery("SELECT rejection_code, COUNT(*) FROM dexcom_messages " +
                "WHERE rejection_code IS NOT NULL GROUP BY rejection_code ORDER BY rejection_code", null).use { c ->
                buildMap {
                    while (c.moveToNext()) {
                        val code = c.getString(0)
                        if (DexcomG7RejectionCode.entries.none { it.code == code }) throw CorruptRow()
                        put(code, c.getLong(1).toCount())
                    }
                }
            }
            val readings = db.rawQuery("SELECT COUNT(*), TOTAL(conflict_of IS NOT NULL) FROM dexcom_readings", null).use { c ->
                check(c.moveToFirst())
                List(2) { c.getLong(it) }
            }
            val last = db.query("dexcom_messages", null, null, null, null, null, "seq DESC", "1").use { c ->
                if (c.moveToFirst()) decodeMessage(c) else null
            }
            result = ObservationSummaryRead.Loaded(ObservationSummary(
                messages = totals[0].toCount(),
                observed = totals[1].toCount(),
                unreadable = totals[2].toCount(),
                rejectedByCode = rejected,
                storedReadings = readings[0].toCount(),
                duplicates = totals[3].toCount(),
                collapsedInMessage = totals[4].toCount(),
                conflicts = readings[1].toCount(),
                lastReceivedAtEpochMs = last?.receivedAtEpochMs,
                lastProvenance = last?.provenance,
            ))
        }
        result
    } catch (failure: RuntimeException) {
        ObservationSummaryRead.Failed(reason(failure, ObservationFailure.READ_FAILED))
    }

    override fun latest(): ObservationLatestRead = try {
        val db = helper.readableDatabase
        var result: ObservationLatestRead = ObservationLatestRead.Failed(ObservationFailure.READ_FAILED)
        db.transaction {
            val last = db.query("dexcom_messages", null, null, null, null, null, "seq DESC", "1").use { c ->
                if (c.moveToFirst()) decodeMessage(c) else null
            }
            val readings = last?.let { readReadings(db, "message_id = ?", arrayOf(it.messageId)) }.orEmpty()
            result = ObservationLatestRead.Loaded(ObservationLatest(last, readings))
        }
        result
    } catch (failure: RuntimeException) {
        ObservationLatestRead.Failed(reason(failure, ObservationFailure.READ_FAILED))
    }

    /** Throws when the block table cannot be read or holds an unknown cause (ADR 0023, part I, point 5). */
    override fun activeBlock(policyVersion: String): GateCause? {
        val db = helper.readableDatabase
        return db.query("dexcom_reception_blocks", arrayOf("cause"), "policy_version = ?", arrayOf(policyVersion),
            null, null, "seq", "1").use { c ->
            if (!c.moveToFirst()) null else GateCause.fromCode(c.getString(0))?.takeIf { it.durable } ?: throw CorruptRow()
        }
    }

    override fun block(policyVersion: String, cause: GateCause, atEpochMs: Long) {
        require(cause.durable) { "dexcom_reception.block_not_durable" }
        helper.writableDatabase.transaction {
            insertOrThrow("dexcom_reception_blocks", null, ContentValues().apply {
                put("policy_version", policyVersion)
                put("cause", cause.code)
                put("blocked_at_ms", atEpochMs)
            })
        }
    }

    /** What a read-only connection found in an existing file before any helper opens it. */
    private enum class Existing { EMPTY_V1, V2 }

    /**
     * Read-only paths (ADR 0021, F4): `readableDatabase` may open for writing and run `onCreate` or `onUpgrade`, so the
     * version and the schema are first checked on a read-only connection. An exact, empty v1 file reads as an empty
     * log without being migrated; any other version or a foreign file is `unsupported_schema` and stays untouched.
     */
    private fun existing(): Existing {
        val path = checkNotNull(file) { "dexcom_observation.in_memory_has_no_file" }.path
        return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY, errorHandler).use { db ->
            when (db.version) {
                DexcomObservationSchema.V2 -> { DexcomObservationSchema.verify(db); Existing.V2 }
                DexcomObservationSchema.V1 -> {
                    DexcomObservationSchema.verify(db, DexcomObservationSchema.V1)
                    val rows = db.rawQuery("SELECT (SELECT COUNT(*) FROM dexcom_messages) + " +
                        "(SELECT COUNT(*) FROM dexcom_readings)", null).use { c -> check(c.moveToFirst()); c.getLong(0) }
                    if (rows != 0L) throw DexcomObservationSchema.Mismatch("v1 log not empty")
                    Existing.EMPTY_V1
                }
                else -> throw DexcomObservationSchema.Mismatch("version ${db.version}")
            }
        }
    }

    /** The summary Diagnóstico reads (ADR 0021, F4), only from an existing log. Never creates or migrates the file. */
    fun summaryOfExisting(): ObservationSummaryRead = try {
        when (existing()) {
            Existing.EMPTY_V1 -> ObservationSummaryRead.Loaded(ObservationSummary(0, 0, 0, emptyMap(), 0, 0, 0, 0, null, null))
            Existing.V2 -> summary()
        }
    } catch (failure: RuntimeException) {
        ObservationSummaryRead.Failed(reason(failure, ObservationFailure.READ_FAILED))
    }

    /** The latest message for Inicio and Bolo (ADR 0023, plan step 5), read-only as [summaryOfExisting]. */
    fun latestOfExisting(): ObservationLatestRead = try {
        when (existing()) {
            Existing.EMPTY_V1 -> ObservationLatestRead.Loaded(ObservationLatest(null, emptyList()))
            Existing.V2 -> latest()
        }
    } catch (failure: RuntimeException) {
        ObservationLatestRead.Failed(reason(failure, ObservationFailure.READ_FAILED))
    }

    /** The durable block of [policyVersion] in an existing log, read-only; throws when it cannot be established. */
    fun activeBlockOfExisting(policyVersion: String): GateCause? = when (existing()) {
        Existing.EMPTY_V1 -> null
        Existing.V2 -> activeBlock(policyVersion)
    }

    private fun Long.toCount(): Int = if (this < 0 || this > Int.MAX_VALUE) throw CorruptRow() else toInt()

    override fun close() = helper.close()

    private fun reason(failure: RuntimeException, fallback: ObservationFailure): ObservationFailure = when (failure) {
        is DexcomObservationSchema.Mismatch -> ObservationFailure.UNSUPPORTED_SCHEMA
        is CorruptRow, is SQLiteDatabaseCorruptException -> ObservationFailure.CORRUPT_STORAGE
        else -> fallback
    }

    private fun insertMessage(db: SQLiteDatabase, message: StoredMessage) {
        val values = ContentValues().apply {
            put("message_id", message.messageId)
            put("received_at_ms", message.receivedAtEpochMs)
            put("provenance", message.provenance.code)
            when (val provenance = message.provenance) {
                is AuthenticatedSender -> {
                    put("sender_package", provenance.packageName)
                    put("sender_version_code", provenance.versionCode)
                    put("sender_signers_sha256", provenance.signerCertificateSha256.joinToString(","))
                }
                is ApprovedPermissionSet -> {
                    put("policy_version", provenance.policyVersion)
                    put("holder_set_sha256", provenance.holderSetSha256)
                }
            }
            put("outcome", message.outcome.code)
            put("contract", message.contract)
            put("rejection_code", message.rejection?.code?.code)
            put("rejection_field", message.rejection?.field)
            put("rejection_index", message.rejection?.index)
            put("new_readings", message.newReadings)
            put("duplicate_readings", message.duplicateReadings)
            put("conflicting_readings", message.conflictingReadings)
            put("collapsed_in_message", message.collapsedInMessage)
            put("unknown_top_level", message.unknownTopLevelFields.joinToString(","))
            put("unknown_reading", message.unknownReadingFields.joinToString(","))
            put("unreportable_unknown", message.unreportableUnknownFieldCount)
        }
        db.insertOrThrow("dexcom_messages", null, values)
    }

    private fun insertReading(db: SQLiteDatabase, reading: StoredReading) {
        val values = ContentValues().apply {
            put("observation_id", reading.observationId)
            put("message_id", reading.messageId)
            put("idx", reading.index)
            put("transmitter_sha256", reading.transmitter.sha256Hex)
            put("producer_timestamp", reading.producerTimestamp)
            put("producer_value", reading.producerGlucoseValue)
            put("producer_trend", reading.producerTrendArrow)
            put("egv_tx_time", reading.egvTxTime)
            put("conflict_of", reading.conflictOf)
            put("status", reading.status.code.removePrefix("dexcom_g7.reading."))
        }
        db.insertOrThrow("dexcom_readings", null, values)
    }

    private fun readMessages(db: SQLiteDatabase, where: String?, args: Array<String>?): List<StoredMessage> =
        db.query("dexcom_messages", null, where, args, null, null, "seq").use { cursor ->
            buildList { while (cursor.moveToNext()) add(decodeMessage(cursor)) }
        }

    private fun readReadings(db: SQLiteDatabase, where: String?, args: Array<String>?): List<StoredReading> =
        db.query("dexcom_readings", null, where, args, null, null, "seq").use { cursor ->
            buildList { while (cursor.moveToNext()) add(decodeReading(cursor)) }
        }

    private fun decodeMessage(c: Cursor): StoredMessage = strict {
        val outcome = StoredMessageOutcome.entries.single { it.code == c.text("outcome") }
        val rejection = c.textOrNull("rejection_code")?.let { code ->
            DexcomG7PayloadRejection(
                DexcomG7RejectionCode.entries.single { it.code == code },
                c.textOrNull("rejection_field"),
                c.intOrNull("rejection_index"),
            )
        }
        StoredMessage(
            messageId = c.text("message_id"),
            receivedAtEpochMs = c.long("received_at_ms"),
            provenance = when (c.text("provenance")) {
                AuthenticatedSender.CODE -> AuthenticatedSender(c.text("sender_package"), c.long("sender_version_code"),
                    c.text("sender_signers_sha256").split(",").toSet().also {
                        if (it.joinToString(",") != c.text("sender_signers_sha256")) throw CorruptRow()
                    })
                ApprovedPermissionSet.CODE -> ApprovedPermissionSet(c.text("policy_version"), c.text("holder_set_sha256"))
                else -> throw CorruptRow()
            },
            outcome = outcome,
            rejection = rejection,
            newReadings = c.int("new_readings"),
            duplicateReadings = c.int("duplicate_readings"),
            conflictingReadings = c.int("conflicting_readings"),
            collapsedInMessage = c.int("collapsed_in_message"),
            unknownTopLevelFields = names(c.text("unknown_top_level")),
            unknownReadingFields = names(c.text("unknown_reading")),
            unreportableUnknownFieldCount = c.int("unreportable_unknown"),
        ).also { if (it.contract != c.text("contract")) throw CorruptRow() }
    }

    private fun decodeReading(c: Cursor): StoredReading = strict {
        StoredReading(
            observationId = c.text("observation_id"),
            messageId = c.text("message_id"),
            index = c.int("idx"),
            transmitter = TransmitterFingerprint(c.text("transmitter_sha256")),
            producerTimestamp = c.long("producer_timestamp"),
            producerGlucoseValue = c.int("producer_value"),
            producerTrendArrow = c.text("producer_trend"),
            egvTxTime = c.long("egv_tx_time"),
            conflictOf = c.textOrNull("conflict_of"),
        ).also { if (c.text("status") != "not_approved_for_use") throw CorruptRow() }
    }

    private fun names(stored: String): List<String> = if (stored.isEmpty()) emptyList() else stored.split(",")

    /** Any decoding or validation failure of a stored row is corruption, never a default. */
    private inline fun <T> strict(block: () -> T): T = try {
        block()
    } catch (_: IllegalArgumentException) {
        throw CorruptRow()
    } catch (_: NoSuchElementException) {
        throw CorruptRow()
    }

    private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)

    private fun Cursor.text(name: String): String {
        val i = column(name)
        if (getType(i) != Cursor.FIELD_TYPE_STRING) throw CorruptRow()
        return getString(i)
    }

    private fun Cursor.textOrNull(name: String): String? = if (isNull(column(name))) null else text(name)

    private fun Cursor.long(name: String): Long {
        val i = column(name)
        if (getType(i) != Cursor.FIELD_TYPE_INTEGER) throw CorruptRow()
        return getLong(i)
    }

    private fun Cursor.int(name: String): Int = long(name).let {
        if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw CorruptRow() else it.toInt()
    }

    private fun Cursor.intOrNull(name: String): Int? = if (isNull(column(name))) null else int(name)

    companion object {
        const val DATABASE_NAME = "dexcom-observations.db"
    }
}
