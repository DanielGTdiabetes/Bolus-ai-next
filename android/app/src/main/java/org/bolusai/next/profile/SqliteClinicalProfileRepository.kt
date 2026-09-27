package org.bolusai.next.profile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.bolusai.profile.*
import java.io.Closeable

/**
 * Device adapter for the clinical profile port (ADR 0012). Own database file, independent of meal drafts.
 * Versions are append-only: triggers abort UPDATE/DELETE and the shared write policy decides every insert
 * inside one exclusive transaction. Every read re-derives fingerprints and fails closed on any inconsistency.
 */
internal class SqliteClinicalProfileRepository(context: Context, name: String? = DATABASE_NAME) :
    ClinicalProfileRepository, Closeable {
    private class UnsupportedSchema : RuntimeException()

    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, SCHEMA_VERSION,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException(ProfileFailure.CORRUPT_STORAGE.code) }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL")
            db.setForeignKeyConstraintsEnabled(true)
        }

        /** Runs inside the helper's transaction: a failure leaves an empty file at user_version 0. */
        override fun onCreate(db: SQLiteDatabase) {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','trigger','index','view') AND " +
                "name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null).use {
                if (it.moveToFirst()) throw UnsupportedSchema()
            }
            SCHEMA.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw UnsupportedSchema() }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw UnsupportedSchema() }
    }

    override fun readVersions(): ProfileRead = try {
        val db = helper.readableDatabase
        var versions: List<ProfileVersion> = emptyList()
        // One transaction keeps both tables consistent with each other.
        db.transaction { versions = readAll(db) }
        // The whole chain is re-validated here too: gaps, provenance and unit transitions (ADR 0012, 4.4).
        if (versions.isNotEmpty()) ProfileHistory.Loaded(versions.sortedByDescending { it.version })
        ProfileRead.Loaded(versions)
    } catch (failure: RuntimeException) {
        ProfileRead.Failed(reason(failure, ProfileFailure.READ_FAILED))
    }

    override fun save(write: ProfileWrite, createdAtEpochMs: Long, writer: String): ProfileSave = try {
        val db = helper.writableDatabase
        var result: ProfileSave = ProfileSave.Failed(ProfileFailure.SAVE_FAILED)
        db.transaction {
            // Never append to a history that cannot be proven: the whole chain is re-validated inside this
            // transaction (contiguity, fingerprints, provenance, unit transitions) before the policy runs.
            val history = readAll(db).takeIf { it.isNotEmpty() }
                ?.let { ProfileHistory.Loaded(it.sortedByDescending { version -> version.version }) }
            val latest = history?.latest
            val atBasePlusOne = if (write.baseVersion >= 0 && write.baseVersion < Long.MAX_VALUE)
                history?.version(write.baseVersion + 1) else null
            val source = write.restoredFrom?.let { history?.version(it) }
            result = when (val decision = ProfileWritePolicy.evaluate(write, createdAtEpochMs, writer, latest,
                atBasePlusOne, source)) {
                is ProfileWriteDecision.Reject -> ProfileSave.Failed(decision.reason)
                is ProfileWriteDecision.Existing -> ProfileSave.Saved(decision.version)
                is ProfileWriteDecision.Insert -> {
                    insert(db, decision.version)
                    // Re-read inside the transaction: what is committed is exactly what the policy approved.
                    check(readVersion(db, decision.version.version) == decision.version)
                    ProfileSave.Saved(decision.version)
                }
            }
        }
        // Including commit failure: Saved is visible only after the transaction returns.
        result
    } catch (failure: RuntimeException) {
        ProfileSave.Failed(reason(failure, ProfileFailure.SAVE_FAILED))
    }

    private fun insert(db: SQLiteDatabase, version: ProfileVersion) {
        val content = version.content
        db.insertOrThrow("profile_versions", null, ContentValues().apply {
            put("version", version.version)
            put("schema_version", content.schemaVersion)
            when (val unit = content.glucoseUnit) {
                Setting.NotConfigured -> putNull("glucose_unit")
                is Setting.Declared -> put("glucose_unit", unit.value.code)
            }
            when (val zone = content.timeZone) {
                Setting.NotConfigured -> putNull("time_zone")
                is Setting.Declared -> put("time_zone", zone.value.id)
            }
            put("content_sha256", version.contentSha256)
            put("origin", version.origin.code)
            version.restoredFrom?.let { put("restored_from", it) } ?: putNull("restored_from")
            put("created_at_ms", version.createdAtEpochMs)
            put("writer", version.writer)
        })
        content.schedules.forEach { schedule ->
            schedule.segments.forEach { segment ->
                db.insertOrThrow("profile_segments", null, ContentValues().apply {
                    put("version", version.version)
                    put("parameter", schedule.parameter.code)
                    put("start_minute", segment.startMinute)
                    put("end_minute", segment.endMinute)
                    when (val value = segment.value) {
                        ProfileValue.NotConfigured -> putNull("value")
                        is ProfileValue.Entered -> put("value", value.decimal.text)
                    }
                })
            }
        }
    }

    private class Row(
        val version: Long, val schemaVersion: Int, val unit: String?, val zone: String?, val sha: String,
        val origin: String, val restoredFrom: Long?, val createdAt: Long, val writer: String,
    )

    private class SegmentRow(val parameter: String, val start: Int, val end: Int, val value: String?)

    private fun readAll(db: SQLiteDatabase): List<ProfileVersion> {
        val rows = db.rawQuery("SELECT * FROM profile_versions ORDER BY version", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        }
        val segments = db.rawQuery(
            "SELECT version, parameter, start_minute, end_minute, value FROM profile_segments ORDER BY version, parameter, start_minute",
            null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to segmentRow(cursor)) }
        }.groupBy({ it.first }, { it.second })
        // Orphan segments (no version row) are corruption, never ignored.
        require(segments.keys.all { number -> rows.any { it.version == number } })
        return rows.map { decode(it, segments[it.version].orEmpty()) }
    }

    private fun readVersion(db: SQLiteDatabase, number: Long): ProfileVersion? {
        val row = db.rawQuery("SELECT * FROM profile_versions WHERE version = ?", arrayOf(number.toString())).use {
            if (it.moveToFirst()) row(it) else null
        } ?: return null
        val segments = db.rawQuery(
            "SELECT version, parameter, start_minute, end_minute, value FROM profile_segments WHERE version = ? ORDER BY parameter, start_minute",
            arrayOf(number.toString())).use { cursor -> buildList { while (cursor.moveToNext()) add(segmentRow(cursor)) } }
        return decode(row, segments)
    }

    private fun Cursor.text(key: String): String? = getColumnIndexOrThrow(key).let { index ->
        when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_STRING -> getString(index)
            else -> throw IllegalArgumentException(ProfileFailure.INVALID_RECORD.code)
        }
    }

    private fun Cursor.integer(key: String): Long? = getColumnIndexOrThrow(key).let { index ->
        when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            else -> throw IllegalArgumentException(ProfileFailure.INVALID_RECORD.code)
        }
    }

    private fun row(cursor: Cursor) = Row(
        requireNotNull(cursor.integer("version")),
        Math.toIntExact(requireNotNull(cursor.integer("schema_version"))),
        cursor.text("glucose_unit"), cursor.text("time_zone"), requireNotNull(cursor.text("content_sha256")),
        requireNotNull(cursor.text("origin")), cursor.integer("restored_from"),
        requireNotNull(cursor.integer("created_at_ms")), requireNotNull(cursor.text("writer")),
    )

    private fun segmentRow(cursor: Cursor): SegmentRow {
        require(cursor.getType(1) == Cursor.FIELD_TYPE_STRING && cursor.getType(2) == Cursor.FIELD_TYPE_INTEGER &&
            cursor.getType(3) == Cursor.FIELD_TYPE_INTEGER &&
            cursor.getType(4) in listOf(Cursor.FIELD_TYPE_NULL, Cursor.FIELD_TYPE_STRING))
        return SegmentRow(cursor.getString(1), Math.toIntExact(cursor.getLong(2)), Math.toIntExact(cursor.getLong(3)),
            if (cursor.isNull(4)) null else cursor.getString(4))
    }

    private fun decode(row: Row, segments: List<SegmentRow>): ProfileVersion {
        val catalog = ProfileCatalog.parameters(row.schemaVersion) ?: throw UnsupportedSchema()
        val unit: Setting<GlucoseUnit> = row.unit?.let { Setting.Declared(requireNotNull(GlucoseUnit.fromCode(it))) }
            ?: Setting.NotConfigured
        val zone: Setting<ProfileTimeZone> = row.zone?.let { Setting.Declared(ProfileTimeZone(it)) } ?: Setting.NotConfigured
        val byParameter = segments.groupBy { requireNotNull(ProfileParameter.fromCode(it.parameter)) }
        require(byParameter.keys == catalog.toSet())
        val content = ProfileContent(row.schemaVersion, unit, zone, catalog.map { parameter ->
            ParameterSchedule(parameter, byParameter.getValue(parameter).sortedBy { it.start }.map {
                TimeSegment(it.start, it.end, it.value?.let { text -> ProfileValue.Entered(CanonicalDecimal(text)) }
                    ?: ProfileValue.NotConfigured)
            })
        })
        val origin = requireNotNull(ProfileOrigin.fromCode(row.origin))
        // ProfileVersion re-derives the fingerprint from the decoded content and rejects any mismatch.
        return ProfileVersion(row.version, content, row.sha, origin, row.restoredFrom, row.createdAt, row.writer)
    }

    private fun reason(failure: RuntimeException, fallback: ProfileFailure): ProfileFailure = when (failure) {
        is UnsupportedSchema -> ProfileFailure.UNSUPPORTED_SCHEMA
        is SQLiteDatabaseCorruptException -> ProfileFailure.CORRUPT_STORAGE
        is IllegalArgumentException, is ArithmeticException, is NoSuchElementException -> ProfileFailure.INVALID_RECORD
        else -> fallback
    }

    override fun close() = helper.close()

    internal companion object {
        const val DATABASE_NAME = "clinical-profile.db"
        const val SCHEMA_VERSION = 1

        /** Shared by creation and by tests that build fixture files; see ADR 0012, section 5. */
        val SCHEMA = listOf(
            """
            CREATE TABLE profile_versions (
                version INTEGER PRIMARY KEY CHECK(version > 0),
                schema_version INTEGER NOT NULL CHECK(schema_version > 0),
                glucose_unit TEXT CHECK(glucose_unit IS NULL OR length(glucose_unit) > 0),
                time_zone TEXT CHECK(time_zone IS NULL OR length(time_zone) BETWEEN 1 AND 64),
                content_sha256 TEXT NOT NULL CHECK(length(content_sha256) = 64),
                origin TEXT NOT NULL CHECK(length(origin) > 0),
                restored_from INTEGER REFERENCES profile_versions(version)
                    CHECK(restored_from IS NULL OR (restored_from > 0 AND restored_from < version - 1)),
                created_at_ms INTEGER NOT NULL,
                writer TEXT NOT NULL CHECK(length(writer) BETWEEN 1 AND 128),
                CHECK(origin <> 'restored' OR restored_from IS NOT NULL)
            )
            """.trimIndent(),
            """
            CREATE TABLE profile_segments (
                version INTEGER NOT NULL REFERENCES profile_versions(version),
                parameter TEXT NOT NULL CHECK(length(parameter) > 0),
                start_minute INTEGER NOT NULL CHECK(start_minute BETWEEN 0 AND 1439),
                end_minute INTEGER NOT NULL CHECK(end_minute BETWEEN 1 AND 1440 AND end_minute > start_minute),
                value TEXT CHECK(value IS NULL OR (length(value) BETWEEN 1 AND 10 AND value NOT GLOB '*[^0-9.]*')),
                PRIMARY KEY(version, parameter, start_minute)
            )
            """.trimIndent(),
            "CREATE TRIGGER profile_versions_immutable_update BEFORE UPDATE ON profile_versions " +
                "BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END",
            "CREATE TRIGGER profile_versions_immutable_delete BEFORE DELETE ON profile_versions " +
                "BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END",
            "CREATE TRIGGER profile_segments_immutable_update BEFORE UPDATE ON profile_segments " +
                "BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END",
            "CREATE TRIGGER profile_segments_immutable_delete BEFORE DELETE ON profile_segments " +
                "BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END",
            "CREATE TRIGGER profile_segments_latest_only BEFORE INSERT ON profile_segments " +
                "WHEN NEW.version <> (SELECT MAX(version) FROM profile_versions) " +
                "BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END",
        )
    }
}

/** Time zone existence from the device tz database; region identifiers only, no fixed offsets. */
internal object AndroidTimeZoneRules : TimeZoneRules {
    override fun exists(id: String): Boolean = id in java.time.ZoneId.getAvailableZoneIds()
}
