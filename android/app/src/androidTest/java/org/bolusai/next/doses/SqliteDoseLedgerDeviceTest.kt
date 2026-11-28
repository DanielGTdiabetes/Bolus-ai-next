package org.bolusai.next.doses

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.insulin.AppendOutcome
import org.bolusai.insulin.DoseEvent
import org.bolusai.insulin.DoseHistoryLog
import org.bolusai.insulin.DoseLedgerCodes
import org.bolusai.insulin.DoseOrigin
import org.bolusai.insulin.LedgerRead
import org.bolusai.insulin.LedgerState
import org.bolusai.next.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ADR 0028 on a synthetic database file. Never opens the app's own insulin-doses.db. */
@RunWith(AndroidJUnit4::class)
class SqliteDoseLedgerDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "synthetic-insulin-doses-test.db"
    private var now = 1_000L
    private val sha = "a".repeat(64)

    @Before fun clean() { context.deleteDatabase(name) }
    @After fun cleanUp() { context.deleteDatabase(name) }

    private fun ledger() = SqliteDoseLedger(context, name)
    private fun log(ledger: SqliteDoseLedger) = DoseHistoryLog(ledger, { now }, "test/synthetic")
    private fun state(ledger: SqliteDoseLedger) = (ledger.read() as LedgerRead.Available).state

    @Test fun aNewFileIsAnUnstartedLedger() {
        ledger().use { assertEquals(LedgerState(null, emptyList(), emptyList()), state(it)) }
    }

    @Test fun eventsSurviveReopeningInOrder() {
        ledger().use { l ->
            val log = log(l)
            assertEquals(AppendOutcome.Appended, log.startTracking("t1"))
            now = 2_000
            assertEquals(AppendOutcome.Appended, log.noteDose("d1", "2.5", DoseOrigin.TEST_CALCULATION, sha))
            now = 3_000
            assertEquals(AppendOutcome.Appended, log.noteDose("d2", "1", DoseOrigin.USER_NOTE, null))
            now = 4_000
            assertEquals(AppendOutcome.Appended, log.voidDose("v1", "d2"))
        }
        ledger().use { l ->
            val state = state(l)
            assertEquals(1_000L, state.trackingSinceEpochMs)
            assertEquals(listOf(DoseEvent.Dose("d1", 2_000, "test/synthetic", "2.5", DoseOrigin.TEST_CALCULATION, sha)),
                state.doses)
            assertEquals(listOf("d2"), state.voided.map { it.eventId })
        }
    }

    @Test fun aRetryIsAReplayAndAnotherContentIsAConflict() {
        ledger().use { l ->
            val log = log(l)
            log.startTracking("t1")
            log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
            now = 9_000
            assertEquals(AppendOutcome.Replayed, log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null))
            assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.EVENT_ID_CONFLICT),
                log.noteDose("d1", "3", DoseOrigin.USER_NOTE, null))
            assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.TRACKING_ALREADY_STARTED), log.startTracking("t2"))
            assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.UNKNOWN_DOSE), log.voidDose("v1", "missing"))
            assertEquals(listOf(1_000L), state(l).doses.map { it.atEpochMs })
        }
    }

    @Test fun rejectedAppendsStoreNothing() {
        ledger().use { l ->
            val log = log(l)
            assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.TRACKING_NOT_STARTED),
                log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null))
            log.startTracking("t1")
            assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.UNITS_NOT_POSITIVE),
                log.noteDose("d1", "0", DoseOrigin.USER_NOTE, null))
        }
        raw().use { db ->
            db.rawQuery("SELECT COUNT(*) FROM dose_events", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
            }
        }
    }

    @Test fun rowsCannotBeUpdatedOrDeleted() {
        ledger().use { l -> log(l).startTracking("t1"); log(l).noteDose("d1", "2", DoseOrigin.USER_NOTE, null) }
        raw().use { db ->
            for (sql in listOf("UPDATE dose_events SET units = '9' WHERE event_id = 'd1'", "DELETE FROM dose_events")) {
                try {
                    db.execSQL(sql)
                    fail(sql)
                } catch (expected: android.database.SQLException) {
                    assertTrue(expected.message.orEmpty().contains("dose_ledger.append_only"))
                }
            }
        }
        ledger().use { assertEquals("2", state(it).doses.single().unitsText) }
    }

    @Test fun aForeignOrAlteredFileIsUnsupportedAndLeftUntouched() {
        raw().use { db ->
            db.execSQL("CREATE TABLE other (x INTEGER)")
            db.version = 1
        }
        ledger().use { l ->
            assertEquals(LedgerRead.Failed(DoseLedgerCodes.UNSUPPORTED_SCHEMA), l.read())
            assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.UNSUPPORTED_SCHEMA), log(l).startTracking("t1"))
        }
        raw().use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'other'", null).use {
                assertTrue(it.moveToFirst())
            }
        }
    }

    @Test fun aNewerFileVersionIsUnsupported() {
        ledger().use { log(it).startTracking("t1") }
        raw().use { it.version = 2 }
        ledger().use { assertEquals(LedgerRead.Failed(DoseLedgerCodes.UNSUPPORTED_SCHEMA), it.read()) }
    }

    @Test fun theAppDatabaseIsDeniedDuringInstrumentation() {
        try {
            SqliteDoseLedger(context)
            fail("the app's own ledger must not open during instrumentation")
        } catch (expected: IllegalStateException) {
            assertEquals(DoseStorageGuard.REAL_DATABASE_DENIED, expected.message)
        }
        val previous = MainActivity.doseLedgerFactory
        MainActivity.doseLedgerFactory = null
        try {
            assertSame(UnavailableDoseLedger, MainActivity.doseLedger(context))
            assertEquals(LedgerRead.Failed(DoseLedgerCodes.UNAVAILABLE_IN_INSTRUMENTATION), UnavailableDoseLedger.read())
        } finally {
            MainActivity.doseLedgerFactory = previous
        }
    }

    private fun raw(): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
}
