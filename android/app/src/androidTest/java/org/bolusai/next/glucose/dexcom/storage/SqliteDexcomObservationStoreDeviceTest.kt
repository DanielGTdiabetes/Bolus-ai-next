package org.bolusai.next.glucose.dexcom.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.next.glucose.dexcom.GateCause
import org.bolusai.dexcomg7.ObservationLatestRead
import org.bolusai.dexcomg7.ObservationLatest
import org.bolusai.dexcomg7.ApprovedPermissionSet
import org.bolusai.dexcomg7.DexcomG7PayloadRejection
import org.bolusai.dexcomg7.DexcomG7RejectionCode
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.dexcomg7.ObservationSummaryRead
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

    private val sender = AuthenticatedSender("example.sender", 42, setOf("b".repeat(64), "a".repeat(64)))

    private fun observed(vararg readings: ObservedDexcomG7Reading, at: Long = 1_000) = ObservationInput.Observed(
        at, sender, ObservedDexcomG7Message("example.sender", readings.toList(), 0, emptyList(), listOf("newField"), 1))

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
            assertEquals(listOf(sender, sender), read.messages.map { it.provenance })
        }
    }

    @Test fun storageSummaryEqualsTheReferenceWithoutLoadingTheHistory() {
        // Codex review on PR #47: Diagnóstico aggregates in SQL; the result must equal ObservationSummary.of(read()).
        store().use { s ->
            assertEquals(ObservationSummaryRead.Loaded(ObservationSummary.of(loaded(s))), s.summary())
            s.record(observed(reading(0, 10), reading(1, 20)))
            s.record(observed(reading(0, 10), reading(1, 20, value = 99)))
            s.record(ObservationInput.PayloadRejected(7, sender, DexcomG7PayloadRejection(DexcomG7RejectionCode.WRONG_TYPE)))
            s.record(ObservationInput.PayloadUnreadable(8, sender))
            val summary = (s.summary() as ObservationSummaryRead.Loaded).summary
            assertEquals(ObservationSummary.of(loaded(s)), summary)
            assertEquals(4, summary.messages)
            assertEquals(1, summary.conflicts)
            assertEquals(mapOf("dexcom_g7.payload.wrong_type" to 1), summary.rejectedByCode)
        }
    }

    @Test fun rejectionsAreStoredWithoutContent() {
        store().use { s ->
            val rejection = DexcomG7PayloadRejection(DexcomG7RejectionCode.WRONG_TYPE, "glucoseValue", 3)
            s.record(ObservationInput.PayloadRejected(5, sender, rejection))
            s.record(ObservationInput.PayloadUnreadable(6, sender))
            val read = loaded(s)
            assertEquals(listOf(StoredMessageOutcome.PAYLOAD_REJECTED, StoredMessageOutcome.PAYLOAD_UNREADABLE),
                read.messages.map { it.outcome })
            assertEquals(rejection, read.messages.first().rejection)
            assertEquals(sender, read.messages.last().provenance)
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
        store().use { s -> s.record(ObservationInput.PayloadUnreadable(1, sender)) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO dexcom_messages (message_id, received_at_ms, outcome, contract, rejection_code, " +
                "new_readings, duplicate_readings, conflicting_readings, collapsed_in_message, unknown_top_level, " +
                "unknown_reading, unreportable_unknown, sender_package, sender_version_code, sender_signers_sha256, provenance) " +
                "VALUES ('raw', 1, 'payload_rejected', " +
                "'modified-dexcom-g7-broadcast-observed-v1', 'dexcom_g7.payload.invented', 0, 0, 0, 0, '', '', 0, 'example.sender', 42, '" + "a".repeat(64) + "', 'authenticated_sender')")
        }
        store().use { s ->
            assertEquals(ObservationRead.Failed(ObservationFailure.CORRUPT_STORAGE), s.read())
            assertEquals(ObservationSummaryRead.Failed(ObservationFailure.CORRUPT_STORAGE), s.summary())
        }
    }

    @Test fun anAlteredSchemaIsRejectedWithoutTouchingTheFile() {
        store().use { s -> s.record(ObservationInput.PayloadUnreadable(1, sender)) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE synthetic_extra (x INTEGER)")
        }
        store().use { s ->
            assertEquals(ObservationRead.Failed(ObservationFailure.UNSUPPORTED_SCHEMA), s.read())
            assertEquals(ObservationWrite.Failed(ObservationFailure.UNSUPPORTED_SCHEMA), s.record(ObservationInput.PayloadUnreadable(2, sender)))
        }
    }

    // ADR 0023, plan step 1: schema v2, provenance of the permission set, durable blocks and the latest message.

    private val permissionSet = ApprovedPermissionSet("adr0023-i2b-2026-10-12", "c".repeat(64))

    private fun writeV1(rows: Boolean) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            DexcomObservationSchema.SCHEMA_V1.forEach(db::execSQL)
            if (rows) db.execSQL("INSERT INTO dexcom_messages (message_id, received_at_ms, sender_package, " +
                "sender_version_code, sender_signers_sha256, outcome, contract, new_readings, duplicate_readings, " +
                "conflicting_readings, collapsed_in_message, unknown_top_level, unknown_reading, unreportable_unknown) " +
                "VALUES ('m', 1, 'example.sender', 1, '${"a".repeat(64)}', 'payload_unreadable', " +
                "'modified-dexcom-g7-broadcast-observed-v1', 0, 0, 0, 0, '', '', 0)")
            db.version = DexcomObservationSchema.V1
        }
    }

    @Test fun anEmptyV1LogIsReadAsEmptyWithoutMigrationAndMigratedByTheWriter() {
        writeV1(rows = false)
        store().use { s ->
            assertEquals(ObservationLatestRead.Loaded(ObservationLatest(null, emptyList())), s.latestOfExisting())
            assertEquals(0, (s.summaryOfExisting() as ObservationSummaryRead.Loaded).summary.messages)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(DexcomObservationSchema.V1, it.version)
        }
        store().use { s -> assertTrue(s.record(ObservationInput.PayloadUnreadable(5, permissionSet)) is ObservationWrite.Recorded) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(DexcomObservationSchema.V2, it.version)
            DexcomObservationSchema.verify(it)
        }
    }

    @Test fun aV1LogWithRowsIsNeverMigratedNorDropped() {
        writeV1(rows = true)
        store().use { s ->
            assertEquals(ObservationWrite.Failed(ObservationFailure.UNSUPPORTED_SCHEMA),
                s.record(ObservationInput.PayloadUnreadable(5, permissionSet)))
            assertEquals(ObservationLatestRead.Failed(ObservationFailure.UNSUPPORTED_SCHEMA), s.latestOfExisting())
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(DexcomObservationSchema.V1, db.version)
            db.rawQuery("SELECT COUNT(*) FROM dexcom_messages", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test fun permissionSetProvenanceRoundTripsAndNeverStoresASender() {
        store().use { s ->
            val message = ObservedDexcomG7Message(null, listOf(reading(0, 10)), 0, emptyList(), emptyList(), 0)
            s.record(ObservationInput.Observed(7, permissionSet, message))
            s.record(observed(reading(0, 20)))
            val read = loaded(s)
            assertEquals(listOf(permissionSet, sender), read.messages.map { it.provenance })
            assertEquals(ObservationLatestRead.Loaded(ObservationLatest.of(read)), s.latest())
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT sender_package, policy_version FROM dexcom_messages ORDER BY seq LIMIT 1", null).use {
                it.moveToFirst()
                assertTrue(it.isNull(0))
                assertEquals("adr0023-i2b-2026-10-12", it.getString(1))
            }
        }
    }

    @Test fun durableBlocksPersistPerPolicyVersionAndAreAppendOnly() {
        store().use { s ->
            assertEquals(null, s.activeBlock("adr0023-i2b-2026-10-12"))
            s.block("adr0023-i2b-2026-10-12", GateCause.HOLDER_SET_CHANGED, 9)
            s.block("adr0023-i2b-2026-10-12", GateCause.SIGNERS_CHANGED, 10)
        }
        store().use { s ->
            assertEquals(GateCause.HOLDER_SET_CHANGED, s.activeBlock("adr0023-i2b-2026-10-12"))
            assertEquals(GateCause.HOLDER_SET_CHANGED, s.activeBlockOfExisting("adr0023-i2b-2026-10-12"))
            assertEquals(null, s.activeBlock("another-policy"))
            assertTrue(runCatching { s.block("p", GateCause.VISIBILITY_UNPROVEN, 1) }.isFailure)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertTrue(runCatching { db.execSQL("DELETE FROM dexcom_reception_blocks") }.isFailure)
            assertTrue(runCatching { db.execSQL("UPDATE dexcom_reception_blocks SET cause = 'x'") }.isFailure)
        }
    }
}
