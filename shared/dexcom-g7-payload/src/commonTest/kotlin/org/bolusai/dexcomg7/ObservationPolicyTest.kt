package org.bolusai.dexcomg7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic messages only (ADR 0020). */
class ObservationPolicyTest {
    /** Reference store: the same transaction contract the SQLite adapter follows, in memory. */
    private class MemoryStore(private val ids: ObservationIds, private val hasher: TransmitterHasher) {
        val messages = mutableListOf<StoredMessage>()
        val readings = mutableListOf<StoredReading>()

        fun record(input: ObservationInput): ObservationPlan {
            val plan = ObservationPolicy.plan(input, ids, hasher) { transmitter, timestamp ->
                readings.filter { it.transmitter == transmitter && it.producerTimestamp == timestamp }
            }
            messages += plan.message
            readings += plan.readings
            return plan
        }
    }

    private class Counter(private val prefix: String = "id") : ObservationIds {
        var n = 0
        override fun next(): String = "$prefix-${++n}"
    }

    /** Deterministic stand-in: a 64-hex string derived from the identifier; the real hash is tested on Android. */
    private val hasher = TransmitterHasher { id ->
        TransmitterFingerprint(id.encodeToByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            .padEnd(64, '0').take(64))
    }

    private fun reading(index: Int, timestamp: Long, value: Int = 120, trend: String = "T", tx: String = "SYNTH-A", egv: Long = 5) =
        ObservedDexcomG7Reading(index, value, timestamp, trend, tx, egv)

    private val sender = AuthenticatedSender("example.sender", 42, setOf("b".repeat(64), "a".repeat(64)))

    private fun observed(vararg readings: ObservedDexcomG7Reading, at: Long = 1_000) = ObservationInput.Observed(
        at,
        sender,
        ObservedDexcomG7Message("example.sender", readings.toList(), 0, emptyList(), emptyList(), 0),
    )

    @Test fun newReadingsAreStoredNotApprovedAndWithoutTheRawTransmitter() {
        val store = MemoryStore(Counter(), hasher)
        val plan = store.record(observed(reading(0, 10), reading(1, 20)))

        assertEquals("id-1", plan.message.messageId)
        assertEquals(StoredMessageOutcome.OBSERVED, plan.message.outcome)
        assertEquals(2, plan.message.newReadings)
        assertEquals(listOf("id-2", "id-3"), plan.readings.map { it.observationId })
        assertTrue(plan.readings.all { it.messageId == "id-1" && it.conflictOf == null })
        assertTrue(plan.readings.all { it.status == ObservedReadingStatus.NOT_APPROVED_FOR_USE })
        assertEquals(hasher.fingerprint("SYNTH-A"), plan.readings.first().transmitter)
        assertFalse(plan.readings.toString().contains("SYNTH-A"))
        assertFalse(plan.message.toString().contains("SYNTH-A"))
    }

    @Test fun anIdenticalReadingInALaterMessageIsADuplicateAndIsNotStoredAgain() {
        val store = MemoryStore(Counter(), hasher)
        store.record(observed(reading(0, 10)))
        val second = store.record(observed(reading(3, 10), reading(4, 30)))

        assertEquals(1, second.message.duplicateReadings)
        assertEquals(1, second.message.newReadings)
        assertEquals(listOf(30L), second.readings.map { it.producerTimestamp })
        assertEquals(2, store.readings.size)
    }

    @Test fun sameKeyWithOtherContentIsKeptAsAConflictOfTheFirstObservation() {
        val store = MemoryStore(Counter(), hasher)
        val first = store.record(observed(reading(0, 10))).readings.single()
        for (variant in listOf(reading(0, 10, value = 121), reading(0, 10, trend = "U"), reading(0, 10, egv = 6))) {
            val plan = store.record(observed(variant))
            assertEquals(1, plan.message.conflictingReadings)
            assertEquals(0, plan.message.newReadings)
            assertEquals(first.observationId, plan.readings.single().conflictOf)
        }
        // Nothing is overwritten: the first observation is unchanged and every variant is kept.
        assertEquals(first, store.readings.first())
        assertEquals(4, store.readings.size)
        assertEquals(1, store.readings.count { it.conflictOf == null })
    }

    @Test fun repeatingAConflictIsADuplicateOfThatConflict() {
        val store = MemoryStore(Counter(), hasher)
        store.record(observed(reading(0, 10)))
        store.record(observed(reading(0, 10, value = 99)))
        val again = store.record(observed(reading(0, 10, value = 99)))
        assertEquals(1, again.message.duplicateReadings)
        assertEquals(0, again.message.conflictingReadings)
        assertEquals(2, store.readings.size)
    }

    @Test fun anotherTransmitterWithTheSameTimestampIsNotAConflict() {
        val store = MemoryStore(Counter(), hasher)
        store.record(observed(reading(0, 10, tx = "SYNTH-A")))
        val other = store.record(observed(reading(0, 10, tx = "SYNTH-B")))
        assertEquals(1, other.message.newReadings)
        assertNull(other.readings.single().conflictOf)
    }

    @Test fun rejectionsKeepOnlyCodeFieldAndIndex() {
        val store = MemoryStore(Counter(), hasher)
        val rejection = DexcomG7PayloadRejection(DexcomG7RejectionCode.WRONG_TYPE, "glucoseValue", 2)
        val rejected = store.record(ObservationInput.PayloadRejected(5, sender, rejection))
        val unreadable = store.record(ObservationInput.PayloadUnreadable(6, sender))

        assertEquals(StoredMessageOutcome.PAYLOAD_REJECTED, rejected.message.outcome)
        assertEquals(rejection, rejected.message.rejection)
        assertEquals(StoredMessageOutcome.PAYLOAD_UNREADABLE, unreadable.message.outcome)
        assertNull(unreadable.message.rejection)
        assertTrue(rejected.readings.isEmpty() && unreadable.readings.isEmpty())
        assertEquals(listOf(5L, 6L), store.messages.map { it.receivedAtEpochMs })
    }

    @Test fun messageKeepsTheParserDiagnostics() {
        val input = ObservationInput.Observed(
            7,
            sender,
            ObservedDexcomG7Message("example.sender", listOf(reading(0, 1)), 2, listOf("a"), listOf("b"), 3),
        )
        val message = MemoryStore(Counter(), hasher).record(input).message
        assertEquals(2, message.collapsedInMessage)
        assertEquals(listOf("a"), message.unknownTopLevelFields)
        assertEquals(listOf("b"), message.unknownReadingFields)
        assertEquals(3, message.unreportableUnknownFieldCount)
        assertEquals(ObservedDexcomG7Contract.ID, message.contract)
    }

    @Test fun aStoreThatAnswersAnotherKeyIsADefect() {
        val wrong = StoredReading("o", "m", 0, hasher.fingerprint("SYNTH-B"), 10, 1, "T", 1, null)
        assertFailsWith<IllegalStateException> {
            ObservationPolicy.plan(observed(reading(0, 10)), Counter(), hasher) { _, _ -> listOf(wrong) }
        }
    }

    @Test fun aConflictWithoutItsFirstObservationIsADefect() {
        val orphan = StoredReading("o2", "m", 0, hasher.fingerprint("SYNTH-A"), 10, 1, "T", 1, "o1")
        assertFailsWith<IllegalStateException> {
            ObservationPolicy.plan(observed(reading(0, 10)), Counter(), hasher) { _, _ -> listOf(orphan) }
        }
    }

    @Test fun recordsRejectInconsistentShapes() {
        assertFailsWith<IllegalArgumentException> { TransmitterFingerprint("ABC") }
        assertFailsWith<IllegalArgumentException> { TransmitterFingerprint("A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> {
            StoredMessage("m", 1, sender, StoredMessageOutcome.PAYLOAD_REJECTED, null, 0, 0, 0, 0, emptyList(), emptyList(), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            StoredMessage("m", 1, sender, StoredMessageOutcome.PAYLOAD_UNREADABLE, null, 1, 0, 0, 0, emptyList(), emptyList(), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            StoredReading("o", "m", 0, hasher.fingerprint("x"), 1, 1, "T", 1, "o")
        }
        assertEquals("TransmitterFingerprint(53594e54…)", hasher.fingerprint("SYNTH").toString())
    }

    @Test fun everyRecordKeepsTheAuthenticatedSender() {
        // Codex review on PR #46: provenance must survive later changes of the trust policy.
        val store = MemoryStore(Counter(), hasher)
        store.record(observed(reading(0, 10)))
        store.record(ObservationInput.PayloadUnreadable(2, sender))
        store.record(ObservationInput.PayloadRejected(3, sender, DexcomG7PayloadRejection(DexcomG7RejectionCode.EMPTY_BATCH)))
        assertTrue(store.messages.all { it.provenance == sender })
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), sender.signerCertificateSha256)
    }

    @Test fun senderMustBeCompleteAndMatchTheParsedMessage() {
        assertFailsWith<IllegalArgumentException> { AuthenticatedSender(" ", 1, setOf("a".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { AuthenticatedSender("p", 0, setOf("a".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { AuthenticatedSender("p", 1, emptySet()) }
        assertFailsWith<IllegalArgumentException> { AuthenticatedSender("p", 1, setOf("A".repeat(64))) }
        assertFailsWith<IllegalArgumentException> {
            ObservationInput.Observed(1, AuthenticatedSender("other.sender", 42, setOf("a".repeat(64))),
                ObservedDexcomG7Message("example.sender", listOf(reading(0, 1)), 0, emptyList(), emptyList(), 0))
        }
    }

    @Test fun storedCodesAreStable() {
        assertEquals(listOf("observed", "payload_rejected", "payload_unreadable"), StoredMessageOutcome.entries.map { it.code })
        assertEquals(
            listOf(
                "dexcom_observation.write_failed",
                "dexcom_observation.read_failed",
                "dexcom_observation.corrupt_storage",
                "dexcom_observation.unsupported_schema",
            ),
            ObservationFailure.entries.map { it.code },
        )
    }
}
