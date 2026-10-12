package org.bolusai.next.profile

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.bolusai.profile.*

/** Read-only schema-1 codec frozen from Next 3355f21; never queries the v3 profile_limits table.
 * Keeps the prior-copy recovery tests independent of the current production row codec.
 */
internal object FrozenV1ProfileRows {
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
        if (row.schemaVersion != 1) throw UnsupportedProfileSchema()
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
