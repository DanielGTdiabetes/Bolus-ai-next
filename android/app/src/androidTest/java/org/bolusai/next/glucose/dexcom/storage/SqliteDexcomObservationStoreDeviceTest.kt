package org.bolusai.next.glucose.dexcom.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.dexcomg7.DexcomG7PayloadRejection
import org.bolusai.dexcomg7.DexcomG7RejectionCode
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservationWrite
import org.bolusai.dexcomg7.ObservedDexcomG7Message
import org.bolusai.dexcomg7.ObservedDexcomG7Reading
import org.bolusai.dexcomg7.StoredMessageOutcome
import org.bolusai.dexcomg7.TransmitterHasher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ADR 0020 on a synthetic database file. Never opens the app's own dexcom-observations.db. */
@RunWith(AndroidJUnit4::class)
class SqliteDexcomObservationStoreDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "synthetic-dexcom-observations-test.db"
    private var n = 0
    private val ids = ObservationIds { "id-${++n}" }

    @Before fun clean() { context.deleteDatabase(name) }
    @After fun cleanUp() { context.deleteDatabase(name) }

    private fun store(hasher: TransmitterHasher = AndroidTransmitterHasher) =
        SqliteDexcomObservationStore(context, name, ids, hasher)

    private fun observed(vararg readings: ObservedDexcomG7Reading, at: Long = 1_000) = ObservationInput.Observed(
        at, ObservedDexcomG7Message("example.sender", readings.toList(), 0, emptyList(), listOf("newField"), 1))

    private fun reading(index: Int, timestamp: Long, value: Int = 120) =
        ObservedDexcomG7Reading(index, value, timestamp, "SYNTHETIC_TREND", "SYNTH-TX", 5)

    private fun loaded(store: SqliteDexcomObservationStore) = store.read() as ObservationRead.Loaded

    @Test fun recordsSurviveReopeningWithDuplicatesAndConflictsKept() {
        store().use { s ->
            assertTrue(s.record(observed(reading(0, 10), reading(1, 20))) is ObservationWrite.Recorded)
            val second = s.record(observed(reading(0, 10), reading(1, 20, value = 99))) as ObservationWrite.Recorded
            assertEquals(1, second.message.duplicateReadings)
            assertEquals(1, second.message.conflictingReadings)
        }
        store().use { s ->
            val read = loaded(s)
            assertEquals(2, read.messages.size)
            assertEquals(3, read.readings.size)
            val conflict = read.readings.single { it.conflictOf != null }
            assertEquals(read.readings.single { it.producerTimestamp == 20L && it.conflictOf == null }.observationId,
                conflict.conflictOf)
            assertEquals(listOf("newField"), read.messages.first().unknownReadingFields)
            assertEquals(AndroidTransmitterHasher.fingerprint("SYNTH-TX"), read.readings.first().transmitter)
        }
    }

    @Test fun rejectionsAreStoredWithoutContent() {
        store().use { s ->
            val rejection = DexcomG7PayloadRejection(DexcomG7RejectionCode.WRONG_TYPE, "glucoseValue", 3)
            s.record(ObservationInput.PayloadRejected(5, rejection))
            s.record(ObservationInput.PayloadUnreadable(6))
            val read = loaded(s)
            assertEquals(listOf(StoredMessageOutcome.PAYLOAD_REJECTED, StoredMessageOutcome.PAYLOAD_UNREADABLE),
                read.messages.map { it.outcome })
            assertEquals(rejection, read.messages.first().rejection)
            assertTrue(read.readings.isEmpty())
        }
    }

    @Test fun theRawTransmitterIsNeverWrittenToTheFile() {
        store().use { s -> s.record(observed(reading(0, 10))) }
        val main = context.getDatabasePath(name)
        val files = listOf(main, java.io.File(main.path + "-wal"), java.io.File(main.path + "-journal")).filter { it.exists() }
        assertTrue(files.isNotEmpty())
        for (file in files) assertFalse(file.name, String(file.readBytes(), Charsets.ISO_8859_1).contains("SYNTH-TX"))
    }

    @Test fun aFailedAppendLeavesNothingBehind() {
        store().use { s -> s.record(observed(reading(0, 10))) }
        val failing = TransmitterHasher { throw IllegalStateException("synthetic hashing failure") }
        store(failing).use { s ->
            assertEquals(ObservationWrite.Failed(ObservationFailure.WRITE_FAILED), s.record(observed(reading(0, 30))))
            val read = loaded(s)
            assertEquals(1, read.messages.size)
            assertEquals(1, read.readings.size)
        }
    }

    @Test fun rowsCannotBeUpdatedOrDeleted() {
        store().use { s -> s.record(observed(reading(0, 10))) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            for (sql in listOf(
                "UPDATE dexcom_readings SET producer_value = 1",
                "DELETE FROM dexcom_readings",
                "UPDATE dexcom_messages SET received_at_ms = 1",
                "DELETE FROM dexcom_messages",
            )) {
                try {
                    db.execSQL(sql)
                    fail("append-only violated by $sql")
                } catch (expected: android.database.SQLException) {
                    assertTrue(expected.message.orEmpty().contains("dexcom_observation.append_only"))
                }
            }
        }
        store().use { s -> assertEquals(1, loaded(s).readings.size) }
    }

    @Test fun aConflictMustPointAtTheFirstObservationOfItsKey() {
        store().use { s -> s.record(observed(reading(0, 10))) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            try {
                db.execSQL("INSERT INTO dexcom_readings (observation_id, message_id, idx, transmitter_sha256, " +
                    "producer_timestamp, producer_value, producer_trend, egv_tx_time, conflict_of, status) " +
                    "SELECT 'x', message_id, 0, transmitter_sha256, 99, 1, 'T', 1, observation_id, " +
                    "'not_approved_for_use' FROM dexcom_readings LIMIT 1")
                fail("conflict with another key accepted")
            } catch (expected: android.database.SQLException) {
                assertTrue(expected.message.orEmpty().contains("dexcom_observation.invalid_conflict"))
            }
        }
    }

    @Test fun anUnknownStoredCodeIsCorruptionNotADefault() {
        store().use { s -> s.record(ObservationInput.PayloadUnreadable(1)) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO dexcom_messages (message_id, received_at_ms, outcome, contract, rejection_code, " +
                "new_readings, duplicate_readings, conflicting_readings, collapsed_in_message, unknown_top_level, " +
                "unknown_reading, unreportable_unknown) VALUES ('raw', 1, 'payload_rejected', " +
                "'modified-dexcom-g7-broadcast-observed-v1', 'dexcom_g7.payload.invented', 0, 0, 0, 0, '', '', 0)")
        }
        store().use { s -> assertEquals(ObservationRead.Failed(ObservationFailure.CORRUPT_STORAGE), s.read()) }
    }

    @Test fun anAlteredSchemaIsRejectedWithoutTouchingTheFile() {
        store().use { s -> s.record(ObservationInput.PayloadUnreadable(1)) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE synthetic_extra (x INTEGER)")
        }
        store().use { s ->
            assertEquals(ObservationRead.Failed(ObservationFailure.UNSUPPORTED_SCHEMA), s.read())
            assertEquals(ObservationWrite.Failed(ObservationFailure.UNSUPPORTED_SCHEMA), s.record(ObservationInput.PayloadUnreadable(2)))
        }
    }
}
