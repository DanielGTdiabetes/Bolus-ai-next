package org.bolusai.next.profile

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.bolusai.profile.*

/** Signals a stored content schema or event kind this build does not know: never guessed, never repaired. */
internal class UnsupportedProfileSchema : RuntimeException()

/**
 * Strict row codec of `clinical-profile.db`, shared by the adapter, the migration and the frozen-v1 test reader.
 * Any unexpected storage type, unknown code or impossible value throws; nothing is coerced or defaulted.
 */
internal object ClinicalProfileRows {
    fun insertVersion(db: SQLiteDatabase, version: ProfileVersion) {
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

    fun insertEvent(db: SQLiteDatabase, event: ConfirmationEvent) {
        db.insertOrThrow("profile_confirmation_events", null, ContentValues().apply {
            put("seq", event.seq)
            put("operation_id", event.operationId.value)
            put("kind", event.kind.code)
            put("contract_version", event.contractVersion)
            event.profileVersion?.let { put("profile_version", it) } ?: putNull("profile_version")
            event.contentSha256?.let { put("content_sha256", it) } ?: putNull("content_sha256")
            event.revokesSeq?.let { put("revokes_seq", it) } ?: putNull("revokes_seq")
            put("observed_event_seq", event.observedEventSeq)
            put("recorded_at_ms", event.recordedAtEpochMs)
            put("writer", event.writer)
        })
    }

    private class Row(
        val version: Long, val schemaVersion: Int, val unit: String?, val zone: String?, val sha: String,
        val origin: String, val restoredFrom: Long?, val createdAt: Long, val writer: String,
    )

    private class SegmentRow(val parameter: String, val start: Int, val end: Int, val value: String?)

    /** Every version, decoded and individually validated; chain rules are validated by the shared domain. */
    fun readVersions(db: SQLiteDatabase): List<ProfileVersion> {
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

    fun readVersion(db: SQLiteDatabase, number: Long): ProfileVersion? {
        val row = db.rawQuery("SELECT * FROM profile_versions WHERE version = ?", arrayOf(number.toString())).use {
            if (it.moveToFirst()) row(it) else null
        } ?: return null
        val segments = db.rawQuery(
            "SELECT version, parameter, start_minute, end_minute, value FROM profile_segments WHERE version = ? ORDER BY parameter, start_minute",
            arrayOf(number.toString())).use { cursor -> buildList { while (cursor.moveToNext()) add(segmentRow(cursor)) } }
        return decode(row, segments)
    }

    /** Every confirmation event, in seq order, each well formed; history rules are validated by the shared domain. */
    fun readEvents(db: SQLiteDatabase): List<ConfirmationEvent> =
        db.rawQuery("SELECT * FROM profile_confirmation_events ORDER BY seq", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val kind = ConfirmationEventKind.fromCode(requireNotNull(cursor.text("kind"))) ?: throw UnsupportedProfileSchema()
                    add(ConfirmationEvent(
                        seq = requireNotNull(cursor.integer("seq")),
                        operationId = OperationId(requireNotNull(cursor.text("operation_id"))),
                        kind = kind,
                        contractVersion = Math.toIntExact(requireNotNull(cursor.integer("contract_version"))),
                        profileVersion = cursor.integer("profile_version"),
                        contentSha256 = cursor.text("content_sha256"),
                        revokesSeq = cursor.integer("revokes_seq"),
                        observedEventSeq = requireNotNull(cursor.integer("observed_event_seq")),
                        recordedAtEpochMs = requireNotNull(cursor.integer("recorded_at_ms")),
                        writer = requireNotNull(cursor.text("writer")),
                    ))
                }
            }
        }

    /** Versions and events from the caller's transaction, validated together (ADR 0014, section 4.5). */
    fun readRecord(db: SQLiteDatabase): ProfileRecordRead = ProfileRecord.validate(readVersions(db), readEvents(db))

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
        val catalog = ProfileCatalog.parameters(row.schemaVersion) ?: throw UnsupportedProfileSchema()
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
}
