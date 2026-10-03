package org.bolusai.next.profile

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Frozen definitions of `clinical-profile.db` and their verification by definition (ADR 0014, sections 8.2 to 8.5).
 * SQLite keeps the original CREATE text in `sqlite_master.sql`, so comparing it byte for byte covers columns, CHECK
 * constraints, foreign keys and trigger bodies. Automatic indexes have no SQL and are checked with PRAGMA.
 */
internal object ClinicalProfileSchema {
    const val V1 = 1
    const val V2 = 2

    internal class Mismatch(message: String) : RuntimeException(message)

    /**
     * Schema v1 exactly as introduced in e78563a and unchanged since (ADR 0012, section 5). Never edit this text: files
     * written by released builds are verified against it before migrating.
     */
    val SCHEMA_V1: List<String> = listOf(
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

    /** Objects added by v2 (ADR 0014, section 8.2). v1 objects are kept unchanged. */
    val V2_ADDITIONS: List<String> = listOf(
        """
        CREATE TABLE profile_confirmation_events (
            seq INTEGER PRIMARY KEY CHECK(seq > 0),
            operation_id TEXT NOT NULL UNIQUE CHECK(length(operation_id) = 36),
            kind TEXT NOT NULL,
            contract_version INTEGER NOT NULL CHECK(contract_version > 0),
            profile_version INTEGER REFERENCES profile_versions(version),
            content_sha256 TEXT CHECK(content_sha256 IS NULL OR length(content_sha256) = 64),
            revokes_seq INTEGER REFERENCES profile_confirmation_events(seq),
            observed_event_seq INTEGER NOT NULL CHECK(observed_event_seq >= 0 AND observed_event_seq < seq),
            recorded_at_ms INTEGER NOT NULL,
            writer TEXT NOT NULL CHECK(length(writer) BETWEEN 1 AND 128),
            CHECK((kind = 'confirm' AND profile_version IS NOT NULL AND content_sha256 IS NOT NULL
                       AND revokes_seq IS NULL)
                OR (kind = 'revoke' AND profile_version IS NULL AND content_sha256 IS NULL
                       AND revokes_seq IS NOT NULL AND revokes_seq < seq))
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX profile_confirmation_one_revocation " +
            "ON profile_confirmation_events(revokes_seq) WHERE revokes_seq IS NOT NULL",
        "CREATE INDEX profile_confirmation_by_version " +
            "ON profile_confirmation_events(profile_version) WHERE profile_version IS NOT NULL",
        "CREATE TRIGGER profile_confirmation_immutable_update BEFORE UPDATE ON profile_confirmation_events " +
            "BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END",
        "CREATE TRIGGER profile_confirmation_immutable_delete BEFORE DELETE ON profile_confirmation_events " +
            "BEGIN SELECT RAISE(ABORT, 'profile.storage.immutable'); END",
        // Contiguous order and observed state equal to the stored one.
        "CREATE TRIGGER profile_confirmation_contiguous BEFORE INSERT ON profile_confirmation_events " +
            "WHEN NEW.seq <> COALESCE((SELECT MAX(seq) FROM profile_confirmation_events), 0) + 1 " +
            "OR NEW.observed_event_seq <> NEW.seq - 1 " +
            "BEGIN SELECT RAISE(ABORT, 'profile.confirmation.state_changed'); END",
        // Only the latest version, with its exact fingerprint.
        "CREATE TRIGGER profile_confirmation_latest_exact BEFORE INSERT ON profile_confirmation_events " +
            "WHEN NEW.kind = 'confirm' AND (" +
            "NEW.profile_version <> (SELECT MAX(version) FROM profile_versions) " +
            "OR NEW.content_sha256 IS NOT (SELECT content_sha256 FROM profile_versions WHERE version = NEW.profile_version)) " +
            "BEGIN SELECT RAISE(ABORT, 'profile.confirmation.stale_version'); END",
        // Never two active confirmations of the same version.
        "CREATE TRIGGER profile_confirmation_single_active BEFORE INSERT ON profile_confirmation_events " +
            "WHEN NEW.kind = 'confirm' AND EXISTS (" +
            "SELECT 1 FROM profile_confirmation_events c " +
            "WHERE c.kind = 'confirm' AND c.profile_version = NEW.profile_version " +
            "AND NOT EXISTS (SELECT 1 FROM profile_confirmation_events r WHERE r.revokes_seq = c.seq)) " +
            "BEGIN SELECT RAISE(ABORT, 'profile.confirmation.already_active'); END",
        // Only a confirmation of the latest version can be revoked.
        "CREATE TRIGGER profile_confirmation_revoke_target BEFORE INSERT ON profile_confirmation_events " +
            "WHEN NEW.kind = 'revoke' AND NOT EXISTS (" +
            "SELECT 1 FROM profile_confirmation_events c " +
            "WHERE c.seq = NEW.revokes_seq AND c.kind = 'confirm' " +
            "AND c.profile_version = (SELECT MAX(version) FROM profile_versions)) " +
            "BEGIN SELECT RAISE(ABORT, 'profile.confirmation.not_active'); END",
    )

    val SCHEMA_V2: List<String> = SCHEMA_V1 + V2_ADDITIONS

    private class Index(val name: String, val unique: Boolean, val origin: String, val partial: Boolean, val columns: List<String>)

    private val SEGMENTS_PK = Index("sqlite_autoindex_profile_segments_1", true, "pk", false,
        listOf("version", "parameter", "start_minute"))
    private val EVENTS_UNIQUE_OPERATION = Index("sqlite_autoindex_profile_confirmation_events_1", true, "u", false,
        listOf("operation_id"))
    private val ONE_REVOCATION = Index("profile_confirmation_one_revocation", true, "c", true, listOf("revokes_seq"))
    private val BY_VERSION = Index("profile_confirmation_by_version", false, "c", true, listOf("profile_version"))

    private fun indexes(version: Int): Map<String, List<Index>> = when (version) {
        V1 -> mapOf("profile_versions" to emptyList(), "profile_segments" to listOf(SEGMENTS_PK))
        V2 -> mapOf("profile_versions" to emptyList(), "profile_segments" to listOf(SEGMENTS_PK),
            "profile_confirmation_events" to listOf(EVENTS_UNIQUE_OPERATION, ONE_REVOCATION, BY_VERSION))
        else -> throw Mismatch("version")
    }

    private fun statements(version: Int): List<String> = when (version) {
        V1 -> SCHEMA_V1
        V2 -> SCHEMA_V2
        else -> throw Mismatch("version")
    }

    private val CREATE_NAME = Regex("^CREATE (TABLE|UNIQUE INDEX|INDEX|TRIGGER) ([a-z_0-9]+) ")

    /** (type, name) -> exact SQL for every object that has SQL. */
    private fun expectedDefinitions(version: Int): Map<Pair<String, String>, String> = statements(version).associateBy { sql ->
        val match = requireNotNull(CREATE_NAME.find(sql)) { sql }
        val type = when (match.groupValues[1]) { "TABLE" -> "table"; "TRIGGER" -> "trigger"; else -> "index" }
        type to match.groupValues[2]
    }

    /**
     * Throws [Mismatch] unless the file holds exactly the objects of [version]: the frozen tables, triggers and named
     * indexes with identical SQL, the expected automatic indexes, and the platform's `android_metadata`. Nothing more.
     */
    fun verify(db: SQLiteDatabase, version: Int) {
        val expected = expectedDefinitions(version)
        val automatic = indexes(version).values.flatten().filter { it.origin != "c" }.map { "index" to it.name }.toSet()
        val actual = mutableMapOf<Pair<String, String>, String?>()
        db.rawQuery("SELECT type, name, sql FROM sqlite_master", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0) to cursor.getString(1)
                if (actual.put(key, if (cursor.isNull(2)) null else cursor.getString(2)) != null) throw Mismatch("duplicate ${key.second}")
            }
        }
        val metadata = "table" to "android_metadata"
        if (actual.keys != expected.keys + automatic + metadata) {
            throw Mismatch("inventory ${actual.keys.sortedBy { it.second }}")
        }
        expected.forEach { (key, sql) -> if (actual[key] != sql) throw Mismatch("definition ${key.second}") }
        automatic.forEach { if (actual[it] != null) throw Mismatch("automatic ${it.second}") }
        if ("locale" !in columns(db, "android_metadata")) throw Mismatch("android_metadata")
        indexes(version).forEach { (table, list) ->
            val found = db.rawQuery("PRAGMA index_list($table)", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(listOf(cursor.text("name"), cursor.text("unique"), cursor.text("origin"),
                        cursor.text("partial")))
                }
            }
            val wanted = list.map { listOf(it.name, if (it.unique) "1" else "0", it.origin, if (it.partial) "1" else "0") }
            if (found.toSet() != wanted.toSet() || found.size != wanted.size) throw Mismatch("indexes $table")
            list.forEach { index ->
                val names = db.rawQuery("PRAGMA index_info(${index.name})", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.text("seqno").toInt() to cursor.text("name")) }
                }.sortedBy { it.first }.map { it.second }
                if (names != index.columns) throw Mismatch("index columns ${index.name}")
            }
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.text("name")) }
        }

    private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))
}
