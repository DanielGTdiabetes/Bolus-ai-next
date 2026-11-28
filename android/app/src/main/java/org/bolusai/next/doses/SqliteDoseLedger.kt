package org.bolusai.next.doses

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import org.bolusai.insulin.AppendOutcome
import org.bolusai.insulin.CalculationRecord
import org.bolusai.insulin.DoseEvent
import org.bolusai.insulin.DoseLedger
import org.bolusai.insulin.DoseLedgerCodes
import org.bolusai.insulin.DoseLedgerRules
import org.bolusai.insulin.DoseOrigin
import org.bolusai.insulin.LedgerRead
import org.bolusai.insulin.LedgerState
import java.io.Closeable

/** A dose ledger the activity can close with its view model. */
internal interface ClosableDoseLedger : DoseLedger, Closeable

/**
 * Barrier for instrumentation, like the profile and Dexcom guards. Once activated by the test runner no code in the
 * process may open the app's own `insulin-doses.db`. Production never activates it; it can only be switched on.
 */
internal object DoseStorageGuard {
    const val REAL_DATABASE_DENIED = "dose_storage.real_database_denied_in_instrumentation"

    @Volatile var active: Boolean = false
        private set

    fun activateForInstrumentation() {
        active = true
    }

    fun checkDatabase(context: Context, name: String?) {
        if (!active || name == null) return
        val target = context.getDatabasePath(name).canonicalFile
        val real = context.getDatabasePath(SqliteDoseLedger.DATABASE_NAME).canonicalFile
        check(target != real) { REAL_DATABASE_DENIED }
    }
}

/** Ledger used when no synthetic ledger was injected during instrumentation: unknown history, never empty. */
internal object UnavailableDoseLedger : ClosableDoseLedger {
    override fun read(): LedgerRead = LedgerRead.Failed(DoseLedgerCodes.UNAVAILABLE_IN_INSTRUMENTATION)
    override fun append(event: DoseEvent): AppendOutcome =
        AppendOutcome.Rejected(DoseLedgerCodes.UNAVAILABLE_IN_INSTRUMENTATION)
    override fun close() = Unit
}

/** Schema text of `insulin-doses.db` v1 (ADR 0028), frozen: SQLite keeps it in `sqlite_master.sql`. */
internal object DoseLedgerSchema {
    const val VERSION = 1

    val V1: List<String> = listOf(
        """
        CREATE TABLE dose_events (
            seq INTEGER PRIMARY KEY,
            event_id TEXT NOT NULL UNIQUE CHECK (length(event_id) > 0),
            kind TEXT NOT NULL CHECK (kind IN ('tracking_started', 'dose', 'void')),
            at_ms INTEGER NOT NULL,
            writer TEXT NOT NULL CHECK (length(writer) > 0),
            units TEXT,
            origin TEXT CHECK (origin IS NULL OR origin IN ('test_calculation', 'user_note')),
            calculation_sha256 TEXT CHECK (calculation_sha256 IS NULL OR length(calculation_sha256) = 64),
            calculation_request TEXT CHECK (calculation_request IS NULL OR length(calculation_request) > 0),
            calculation_result TEXT CHECK (calculation_result IS NULL OR length(calculation_result) > 0),
            void_of TEXT REFERENCES dose_events(event_id),
            status TEXT NOT NULL CHECK (status = 'local_note_not_treatment_authority'),
            CHECK ((kind = 'dose') = (units IS NOT NULL AND origin IS NOT NULL)),
            CHECK (kind = 'dose' OR (units IS NULL AND origin IS NULL AND calculation_sha256 IS NULL)),
            CHECK ((calculation_sha256 IS NULL) = (calculation_request IS NULL) AND
                (calculation_sha256 IS NULL) = (calculation_result IS NULL)),
            CHECK ((origin = 'test_calculation') = (calculation_sha256 IS NOT NULL) OR origin IS NULL),
            CHECK ((kind = 'void') = (void_of IS NOT NULL))
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX dose_events_single_tracking ON dose_events (kind) WHERE kind = 'tracking_started'",
        "CREATE UNIQUE INDEX dose_events_single_void ON dose_events (void_of) WHERE void_of IS NOT NULL",
        "CREATE TRIGGER dose_events_immutable_update BEFORE UPDATE ON dose_events " +
            "BEGIN SELECT RAISE(ABORT, 'dose_ledger.append_only'); END",
        "CREATE TRIGGER dose_events_immutable_delete BEFORE DELETE ON dose_events " +
            "BEGIN SELECT RAISE(ABORT, 'dose_ledger.append_only'); END",
        "CREATE TRIGGER dose_events_void_target BEFORE INSERT ON dose_events WHEN NEW.void_of IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM dose_events d WHERE d.event_id = NEW.void_of AND d.kind = 'dose') " +
            "BEGIN SELECT RAISE(ABORT, 'dose_ledger.invalid_void_target'); END",
    )

    private val CREATE_NAME = Regex("^CREATE (?:TABLE|UNIQUE INDEX|TRIGGER) ([a-z_0-9]+) ")

    /** True when the file holds exactly the v1 objects with identical SQL (plus SQLite's own objects). */
    fun matches(db: SQLiteDatabase): Boolean {
        val expected = V1.associateBy { requireNotNull(CREATE_NAME.find(it)).groupValues[1] }
        val actual = HashMap<String, String?>()
        db.rawQuery("SELECT name, sql FROM sqlite_master", null).use {
            while (it.moveToNext()) actual[it.getString(0)] = if (it.isNull(1)) null else it.getString(1)
        }
        val own = actual.filterKeys { it != "android_metadata" && !it.startsWith("sqlite_autoindex_") }
        return own == expected
    }
}

/**
 * Device adapter of the dose ledger (ADR 0028). Append-only by triggers, `synchronous=FULL`, one exclusive
 * transaction per append. It never deletes, rewrites or repairs: a file that is not exactly v1 or holds an
 * inconsistent row is reported as failed and left untouched.
 */
internal class SqliteDoseLedger(context: Context, name: String? = DATABASE_NAME) : ClosableDoseLedger {
    private class UnsupportedSchema : RuntimeException()

    init { DoseStorageGuard.checkDatabase(context, name) }

    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, DoseLedgerSchema.VERSION,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("dose_ledger.corrupt") }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL")
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.rawQuery("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
                null).use { if (it.moveToFirst()) throw UnsupportedSchema() }
            DoseLedgerSchema.V1.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw UnsupportedSchema() }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw UnsupportedSchema() }
        override fun onOpen(db: SQLiteDatabase) { if (!DoseLedgerSchema.matches(db)) throw UnsupportedSchema() }
    }

    override fun read(): LedgerRead = try {
        state(helper.readableDatabase)?.let { LedgerRead.Available(it) } ?: LedgerRead.Failed(DoseLedgerCodes.CORRUPT_ROW)
    } catch (_: UnsupportedSchema) {
        LedgerRead.Failed(DoseLedgerCodes.UNSUPPORTED_SCHEMA)
    } catch (_: RuntimeException) {
        LedgerRead.Failed(DoseLedgerCodes.READ_FAILED)
    }

    override fun append(event: DoseEvent): AppendOutcome = try {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val events = events(db)
            val state = events?.let(LedgerState::fold)
            if (events == null || state == null) {
                AppendOutcome.Rejected(DoseLedgerCodes.CORRUPT_ROW)
            } else {
                val outcome = DoseLedgerRules.check(state, events.firstOrNull { it.eventId == event.eventId }, event)
                if (outcome == AppendOutcome.Appended) {
                    db.insertOrThrow("dose_events", null, row(event))
                    db.setTransactionSuccessful()
                }
                outcome
            }
        } finally {
            db.endTransaction()
        }
    } catch (_: UnsupportedSchema) {
        AppendOutcome.Rejected(DoseLedgerCodes.UNSUPPORTED_SCHEMA)
    } catch (_: RuntimeException) {
        AppendOutcome.Rejected(DoseLedgerCodes.WRITE_FAILED)
    }

    override fun close() = helper.close()

    private fun state(db: SQLiteDatabase): LedgerState? = events(db)?.let(LedgerState::fold)

    /** Every event in append order, or null when a row cannot be decoded strictly. */
    private fun events(db: SQLiteDatabase): List<DoseEvent>? {
        val events = mutableListOf<DoseEvent>()
        db.rawQuery("SELECT event_id, kind, at_ms, writer, units, origin, calculation_sha256, void_of, status, " +
            "calculation_request, calculation_result FROM dose_events ORDER BY seq", null).use {
            while (it.moveToNext()) events += decode(it) ?: return null
        }
        return events
    }

    private fun decode(c: Cursor): DoseEvent? {
        if (c.getString(8) != DoseLedgerCodes.STATUS) return null
        val id = c.getString(0)
        val at = c.getLong(2)
        val writer = c.getString(3)
        return when (c.getString(1)) {
            "tracking_started" -> DoseEvent.TrackingStarted(id, at, writer)
            "dose" -> DoseEvent.Dose(id, at, writer, c.getString(4), DoseOrigin.fromCode(c.getString(5)) ?: return null,
                if (c.isNull(6)) null else CalculationRecord(c.getString(6), c.getString(9), c.getString(10)))
            "void" -> DoseEvent.Void(id, at, writer, c.getString(7))
            else -> null
        }
    }

    private fun row(event: DoseEvent) = ContentValues().apply {
        put("event_id", event.eventId)
        put("at_ms", event.atEpochMs)
        put("writer", event.writer)
        put("status", DoseLedgerCodes.STATUS)
        when (event) {
            is DoseEvent.TrackingStarted -> put("kind", "tracking_started")
            is DoseEvent.Dose -> {
                put("kind", "dose")
                put("units", event.unitsText)
                put("origin", event.origin.code)
                event.calculation?.let {
                    put("calculation_sha256", it.inputSha256)
                    put("calculation_request", it.requestText)
                    put("calculation_result", it.resultText)
                }
            }
            is DoseEvent.Void -> {
                put("kind", "void")
                put("void_of", event.doseEventId)
            }
        }
    }

    companion object {
        const val DATABASE_NAME = "insulin-doses.db"
    }
}
