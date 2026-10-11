package org.bolusai.next.glucose.dexcom.storage

import org.bolusai.dexcomg7.DexcomG7PayloadRejection
import org.bolusai.dexcomg7.DexcomG7RejectionCode
import org.bolusai.dexcomg7.DexcomObservationStore
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservationWrite
import org.bolusai.dexcomg7.ObservedDexcomG7Message
import org.bolusai.dexcomg7.ObservedDexcomG7Reading
import org.bolusai.dexcomg7.StoredMessage
import org.bolusai.dexcomg7.StoredMessageOutcome
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.next.glucose.dexcom.DexcomG7ReceptionOutcome
import org.bolusai.next.glucose.dexcom.DexcomSenderAuthenticationFailure
import org.bolusai.next.glucose.dexcom.rejected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic outcomes only (ADR 0020). The SQLite store is covered by SqliteDexcomObservationStoreDeviceTest. */
class DexcomG7RecorderTest {
    private class FakeStore(private val fail: Boolean = false) : DexcomObservationStore {
        val inputs = mutableListOf<ObservationInput>()
        override fun record(input: ObservationInput): ObservationWrite {
            inputs += input
            if (fail) return ObservationWrite.Failed(ObservationFailure.WRITE_FAILED)
            val outcome = when (input) {
                is ObservationInput.Observed -> StoredMessageOutcome.OBSERVED
                is ObservationInput.PayloadRejected -> StoredMessageOutcome.PAYLOAD_REJECTED
                is ObservationInput.PayloadUnreadable -> StoredMessageOutcome.PAYLOAD_UNREADABLE
            }
            val rejection = (input as? ObservationInput.PayloadRejected)?.rejection
            return ObservationWrite.Recorded(
                StoredMessage("m", input.receivedAtEpochMs, outcome, rejection, 0, 0, 0, 0, emptyList(), emptyList(), 0),
                emptyList(),
            )
        }
        override fun read(): ObservationRead = error("unused")
    }

    private val clock = EpochClock { 42L }
    private val observed = DexcomG7ReceptionOutcome.Observed(
        ObservedDexcomG7Message("example.sender", listOf(ObservedDexcomG7Reading(0, 1, 2, "T", "SYNTH-TX", 3)), 0,
            emptyList(), emptyList(), 0),
    )

    @Test
    fun `unauthenticated senders are never stored and keep their reason`() {
        val store = FakeStore()
        val outcome = DexcomG7ReceptionOutcome.SenderRejected(rejected(DexcomSenderAuthenticationFailure.UID_MISMATCH))
        val recording = DexcomG7Recorder(store, EpochClock { error("clock must not be read") }).record(outcome)
        assertEquals(DexcomG7Recording.NotRecorded(outcome.glucose), recording)
        assertTrue(store.inputs.isEmpty())
    }

    @Test
    fun `authenticated outcomes are stored with the injected clock and stay blocked`() {
        val store = FakeStore()
        val rejection = DexcomG7PayloadRejection(DexcomG7RejectionCode.EMPTY_BATCH, "glucoseValues")
        val outcomes = listOf(
            observed,
            DexcomG7ReceptionOutcome.PayloadRejected(rejection),
            DexcomG7ReceptionOutcome.PayloadUnreadable,
        )
        val recordings = outcomes.map { DexcomG7Recorder(store, clock).record(it) }
        assertEquals(
            listOf(
                ObservationInput.Observed(42, observed.message),
                ObservationInput.PayloadRejected(42, rejection),
                ObservationInput.PayloadUnreadable(42),
            ),
            store.inputs,
        )
        assertTrue(recordings.all { it is DexcomG7Recording.Recorded })
        assertEquals(outcomes.map { it.glucose }, recordings.map { it.glucose })
        assertTrue(recordings.none { it.glucose.reason == UnavailabilityReason.PERSISTENCE_FAILED })
    }

    @Test
    fun `a failed append blocks as persistence failed and is never reported as recorded`() {
        val recording = DexcomG7Recorder(FakeStore(fail = true), clock).record(observed)
        assertEquals(DexcomG7Recording.Failed(ObservationWrite.Failed(ObservationFailure.WRITE_FAILED)), recording)
        assertEquals("input.glucose.persistence_failed", recording.glucose.code)
    }

    @Test
    fun `store defects propagate unchanged`() {
        val defect = IllegalStateException("synthetic store defect")
        val store = object : DexcomObservationStore {
            override fun record(input: ObservationInput): ObservationWrite = throw defect
            override fun read(): ObservationRead = error("unused")
        }
        assertSame(defect, assertThrows(IllegalStateException::class.java) { DexcomG7Recorder(store, clock).record(observed) })
    }

    @Test
    fun `transmitter fingerprint is a domain separated SHA-256`() {
        // python3: hashlib.sha256(b'bolusai.dexcom.transmitter.v1\x00SYNTH-TX').hexdigest()
        assertEquals(
            "910dc0493e670962e2747a92cb121a873bbe788064b0d0053d18505585358bb8",
            AndroidTransmitterHasher.fingerprint("SYNTH-TX").sha256Hex,
        )
        assertEquals(AndroidTransmitterHasher.fingerprint("a"), AndroidTransmitterHasher.fingerprint("a"))
        assertTrue(AndroidTransmitterHasher.fingerprint("a") != AndroidTransmitterHasher.fingerprint("b"))
    }
}
