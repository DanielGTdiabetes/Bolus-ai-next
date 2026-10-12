package org.bolusai.next.glucose.dexcom.storage

import android.database.sqlite.SQLiteDatabase

/**
 * Schema texts of `dexcom-observations.db`, frozen per version: SQLite keeps them in `sqlite_master.sql`. v1 is ADR 0020.
 * v2 (ADR 0023, plan step 1) stores the provenance of each message, so a message admitted by the approved permission
 * set never carries a fabricated sender, and adds the append-only durable blocks of the reception gate.
 */
internal object DexcomObservationSchema {
    const val V1 = 1
    const val V2 = 2

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

    private val MESSAGES_V2 = """
        CREATE TABLE dexcom_messages (
            seq INTEGER PRIMARY KEY,
            message_id TEXT NOT NULL UNIQUE CHECK (length(message_id) > 0),
            received_at_ms INTEGER NOT NULL,
            provenance TEXT NOT NULL CHECK (provenance IN ('authenticated_sender', 'approved_permission_set')),
            sender_package TEXT CHECK (sender_package IS NULL OR length(sender_package) > 0),
            sender_version_code INTEGER CHECK (sender_version_code IS NULL OR sender_version_code > 0),
            sender_signers_sha256 TEXT CHECK (sender_signers_sha256 IS NULL OR length(sender_signers_sha256) >= 64),
            policy_version TEXT CHECK (policy_version IS NULL OR length(policy_version) > 0),
            holder_set_sha256 TEXT CHECK (holder_set_sha256 IS NULL OR length(holder_set_sha256) = 64),
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
            CHECK ((provenance = 'authenticated_sender') = (sender_package IS NOT NULL AND
                sender_version_code IS NOT NULL AND sender_signers_sha256 IS NOT NULL)),
            CHECK ((provenance = 'approved_permission_set') = (policy_version IS NOT NULL AND holder_set_sha256 IS NOT NULL)),
            CHECK (sender_package IS NULL OR policy_version IS NULL),
            CHECK ((outcome = 'payload_rejected') = (rejection_code IS NOT NULL)),
            CHECK (rejection_code IS NOT NULL OR (rejection_field IS NULL AND rejection_index IS NULL))
        )
        """.trimIndent()

    /** v2: the v1 readings, indexes and triggers unchanged, the new messages table and the durable blocks. */
    val SCHEMA_V2: List<String> = listOf(MESSAGES_V2) + SCHEMA_V1.drop(1) + listOf(
        """
        CREATE TABLE dexcom_reception_blocks (
            seq INTEGER PRIMARY KEY,
            policy_version TEXT NOT NULL CHECK (length(policy_version) > 0),
            cause TEXT NOT NULL CHECK (length(cause) > 0),
            blocked_at_ms INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE TRIGGER dexcom_reception_blocks_immutable_update BEFORE UPDATE ON dexcom_reception_blocks " +
            "BEGIN SELECT RAISE(ABORT, 'dexcom_observation.append_only'); END",
        "CREATE TRIGGER dexcom_reception_blocks_immutable_delete BEFORE DELETE ON dexcom_reception_blocks " +
            "BEGIN SELECT RAISE(ABORT, 'dexcom_observation.append_only'); END",
    )

    private val CREATE_NAME = Regex("^CREATE (TABLE|UNIQUE INDEX|INDEX|TRIGGER) ([a-z_0-9]+) ")

    private val AUTOMATIC = setOf(
        "index" to "sqlite_autoindex_dexcom_messages_1",
        "index" to "sqlite_autoindex_dexcom_readings_1",
    )

    fun schema(version: Int): List<String> = when (version) {
        V1 -> SCHEMA_V1
        V2 -> SCHEMA_V2
        else -> throw Mismatch("version $version")
    }

    private fun expected(version: Int): Map<Pair<String, String>, String> = schema(version).associateBy { sql ->
        val match = requireNotNull(CREATE_NAME.find(sql)) { sql }
        val type = when (match.groupValues[1]) { "TABLE" -> "table"; "TRIGGER" -> "trigger"; else -> "index" }
        type to match.groupValues[2]
    }

    /** Throws [Mismatch] unless the file holds exactly the objects of [version] with identical SQL, plus `android_metadata`. */
    fun verify(db: SQLiteDatabase, version: Int = V2) {
        val expected = expected(version)
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

    /**
     * v1 -> v2. v1 never received a production message: the strict policy stayed Pending, so the app's own file is
     * empty or absent. Only an exact, empty v1 file is replaced; any row keeps the file untouched and fails as
     * unsupported_schema, so no history is dropped or relabelled. Runs inside the open helper's upgrade transaction.
     */
    fun upgradeEmptyV1(db: SQLiteDatabase) {
        verify(db, V1)
        for (table in listOf("dexcom_readings", "dexcom_messages")) {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                check(it.moveToFirst())
                if (it.getLong(0) != 0L) throw Mismatch("v1 log not empty")
            }
        }
        db.execSQL("DROP TABLE dexcom_readings")
        db.execSQL("DROP TABLE dexcom_messages")
        SCHEMA_V2.forEach(db::execSQL)
        verify(db, V2)
    }
}
