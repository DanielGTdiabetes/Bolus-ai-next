package org.bolusai.next.glucose.dexcom.storage

import android.database.sqlite.SQLiteDatabase

/** The schema text of `dexcom-observations.db` v1 is frozen (ADR 0020): SQLite keeps it in `sqlite_master.sql`. */
internal object DexcomObservationSchema {
    const val V1 = 1

    internal class Mismatch(detail: String) : RuntimeException("dexcom_observation.unsupported_schema: $detail")

    val SCHEMA_V1: List<String> = listOf(
        """
        CREATE TABLE dexcom_messages (
            seq INTEGER PRIMARY KEY,
            message_id TEXT NOT NULL UNIQUE CHECK (length(message_id) > 0),
            received_at_ms INTEGER NOT NULL,
            sender_package TEXT NOT NULL CHECK (length(sender_package) > 0),
            sender_version_code INTEGER NOT NULL CHECK (sender_version_code > 0),
            sender_signers_sha256 TEXT NOT NULL CHECK (length(sender_signers_sha256) >= 64),
            outcome TEXT NOT NULL CHECK (outcome IN ('observed', 'payload_rejected', 'payload_unreadable')),
            contract TEXT NOT NULL CHECK (contract = 'modified-dexcom-g7-broadcast-observed-v1'),
            rejection_code TEXT,
            rejection_field TEXT,
            rejection_index INTEGER CHECK (rejection_index IS NULL OR rejection_index >= 0),
            new_readings INTEGER NOT NULL CHECK (new_readings >= 0),
            duplicate_readings INTEGER NOT NULL CHECK (duplicate_readings >= 0),
            conflicting_readings INTEGER NOT NULL CHECK (conflicting_readings >= 0),
            collapsed_in_message INTEGER NOT NULL CHECK (collapsed_in_message >= 0),
            unknown_top_level TEXT NOT NULL,
            unknown_reading TEXT NOT NULL,
            unreportable_unknown INTEGER NOT NULL CHECK (unreportable_unknown >= 0),
            CHECK ((outcome = 'payload_rejected') = (rejection_code IS NOT NULL)),
            CHECK (rejection_code IS NOT NULL OR (rejection_field IS NULL AND rejection_index IS NULL))
        )
        """.trimIndent(),
        """
        CREATE TABLE dexcom_readings (
            seq INTEGER PRIMARY KEY,
            observation_id TEXT NOT NULL UNIQUE CHECK (length(observation_id) > 0),
            message_id TEXT NOT NULL REFERENCES dexcom_messages(message_id),
            idx INTEGER NOT NULL CHECK (idx >= 0),
            transmitter_sha256 TEXT NOT NULL CHECK (length(transmitter_sha256) = 64),
            producer_timestamp INTEGER NOT NULL,
            producer_value INTEGER NOT NULL,
            producer_trend TEXT NOT NULL CHECK (length(producer_trend) > 0),
            egv_tx_time INTEGER NOT NULL,
            conflict_of TEXT REFERENCES dexcom_readings(observation_id),
            status TEXT NOT NULL CHECK (status = 'not_approved_for_use'),
            CHECK (conflict_of IS NULL OR conflict_of != observation_id)
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX dexcom_readings_first ON dexcom_readings (transmitter_sha256, producer_timestamp) " +
            "WHERE conflict_of IS NULL",
        "CREATE INDEX dexcom_readings_by_key ON dexcom_readings (transmitter_sha256, producer_timestamp)",
        "CREATE TRIGGER dexcom_messages_immutable_update BEFORE UPDATE ON dexcom_messages " +
            "BEGIN SELECT RAISE(ABORT, 'dexcom_observation.append_only'); END",
        "CREATE TRIGGER dexcom_messages_immutable_delete BEFORE DELETE ON dexcom_messages " +
            "BEGIN SELECT RAISE(ABORT, 'dexcom_observation.append_only'); END",
        "CREATE TRIGGER dexcom_readings_immutable_update BEFORE UPDATE ON dexcom_readings " +
            "BEGIN SELECT RAISE(ABORT, 'dexcom_observation.append_only'); END",
        "CREATE TRIGGER dexcom_readings_immutable_delete BEFORE DELETE ON dexcom_readings " +
            "BEGIN SELECT RAISE(ABORT, 'dexcom_observation.append_only'); END",
        // A conflict must point at the first observation of the same transmitter and producer timestamp.
        "CREATE TRIGGER dexcom_readings_conflict_target BEFORE INSERT ON dexcom_readings " +
            "WHEN NEW.conflict_of IS NOT NULL AND NOT EXISTS (SELECT 1 FROM dexcom_readings r " +
            "WHERE r.observation_id = NEW.conflict_of AND r.conflict_of IS NULL " +
            "AND r.transmitter_sha256 = NEW.transmitter_sha256 AND r.producer_timestamp = NEW.producer_timestamp) " +
            "BEGIN SELECT RAISE(ABORT, 'dexcom_observation.invalid_conflict'); END",
    )

    private val CREATE_NAME = Regex("^CREATE (TABLE|UNIQUE INDEX|INDEX|TRIGGER) ([a-z_0-9]+) ")

    private val AUTOMATIC = setOf(
        "index" to "sqlite_autoindex_dexcom_messages_1",
        "index" to "sqlite_autoindex_dexcom_readings_1",
    )

    private fun expected(): Map<Pair<String, String>, String> = SCHEMA_V1.associateBy { sql ->
        val match = requireNotNull(CREATE_NAME.find(sql)) { sql }
        val type = when (match.groupValues[1]) { "TABLE" -> "table"; "TRIGGER" -> "trigger"; else -> "index" }
        type to match.groupValues[2]
    }

    /** Throws [Mismatch] unless the file holds exactly the v1 objects with identical SQL, plus `android_metadata`. */
    fun verify(db: SQLiteDatabase) {
        val expected = expected()
        val actual = mutableMapOf<Pair<String, String>, String?>()
        db.rawQuery("SELECT type, name, sql FROM sqlite_master", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0) to cursor.getString(1)
                if (actual.put(key, if (cursor.isNull(2)) null else cursor.getString(2)) != null) {
                    throw Mismatch("duplicate ${key.second}")
                }
            }
        }
        val metadata = "table" to "android_metadata"
        if (actual.keys != expected.keys + AUTOMATIC + metadata) throw Mismatch("inventory")
        expected.forEach { (key, sql) -> if (actual[key] != sql) throw Mismatch("definition ${key.second}") }
        AUTOMATIC.forEach { if (actual[it] != null) throw Mismatch("automatic ${it.second}") }
    }
}
