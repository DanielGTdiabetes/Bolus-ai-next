package org.bolusai.next.glucose.dexcom

import android.content.pm.PackageManager
import org.bolusai.dexcomg7.DexcomG7Payload
import org.bolusai.dexcomg7.DexcomObservationStore
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservationSummaryRead
import org.bolusai.dexcomg7.ObservationWrite
import org.bolusai.dexcomg7.ObservedDexcomG7Contract
import org.bolusai.dexcomg7.PayloadValue
import org.bolusai.dexcomg7.StoredMessage
import org.bolusai.dexcomg7.StoredMessageOutcome
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.next.glucose.dexcom.storage.AndroidObservationIds
import org.bolusai.next.glucose.dexcom.storage.DexcomG7Recording
import org.bolusai.next.glucose.dexcom.storage.EpochClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/** ADR 0022: receiver logic with synthetic senders, payloads and stores only. No real Dexcom data. */
class DexcomG7IntakeTest {
    private class Store(private val fail: Boolean = false, private val defect: Throwable? = null) :
        DexcomObservationStore, Closeable {
        val inputs = mutableListOf<ObservationInput>()
        var closed = 0
        override fun record(input: ObservationInput): ObservationWrite {
            defect?.let { throw it }
            inputs += input
            if (fail) return ObservationWrite.Failed(ObservationFailure.WRITE_FAILED)
            return ObservationWrite.Recorded(
                StoredMessage("m", input.receivedAtEpochMs, input.sender, StoredMessageOutcome.OBSERVED, null, 1, 0, 0, 0,
                    emptyList(), emptyList(), 0),
                emptyList(),
            )
        }
        override fun read(): ObservationRead = error("unused")
        override fun summary(): ObservationSummaryRead = error("unused")
        override fun close() { closed++ }
    }

    /** Fails if touched: proves that nothing about the sender was looked at. */
    private object Untouchable : DexcomSenderEvidenceSource {
        override fun attributedIdentity(): AttributedSenderIdentity = error("sender must not be authenticated")
        override fun resolvePackage(packageName: String): SenderPackageResolution = error("sender must not be resolved")
    }

    private val clock = EpochClock { 42L }

    @Test
    fun `another action is ignored without authenticating or reading`() {
        val step = DexcomG7Intake(ACTION, policy()).accept("example.OTHER", Untouchable) { error("must not read") }
        assertEquals(DexcomG7Intake.Step.Done(DexcomReceipt.IgnoredAction("example.OTHER")), step)
        val missing = DexcomG7Intake(ACTION, policy()).accept(null, Untouchable) { error("must not read") }
        assertEquals(DexcomG7Intake.Step.Done(DexcomReceipt.IgnoredAction(null)), missing)
    }

    @Test
    fun `the pending production policy rejects every sender before reading extras`() {
        assertSame(DexcomSenderTrustPolicy.Pending, DexcomReceiverConfiguration.policy)
        assertEquals(ObservedDexcomG7Contract.ACTION, DexcomReceiverConfiguration.ACTION)
        val step = DexcomG7Intake(DexcomReceiverConfiguration.ACTION, DexcomReceiverConfiguration.policy)
            .accept(DexcomReceiverConfiguration.ACTION, Untouchable) { error("must not read") }
        val receipt = (step as DexcomG7Intake.Step.Done).receipt as DexcomReceipt.SenderRejected
        assertEquals(0, receipt.payloadReads)
        assertEquals(DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED, receipt.outcome.authentication.failure)
        assertEquals("input.glucose.policy_not_approved", receipt.outcome.glucose.code)
    }

    @Test
    fun `a rejected sender never reaches the store`() {
        val step = DexcomG7Intake(ACTION, policy()).accept(ACTION, Source(AttributedSenderIdentity(null, null))) {
            error("must not read")
        }
        val receipt = (step as DexcomG7Intake.Step.Done).receipt as DexcomReceipt.SenderRejected
        assertEquals(0, receipt.payloadReads)
        assertEquals(DexcomSenderAuthenticationFailure.IDENTITY_NOT_SHARED, receipt.outcome.authentication.failure)
    }

    @Test
    fun `an authenticated message is read once, recorded once and stays blocked`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, Source()) { DexcomExtrasRead.Read(payload()) } as DexcomG7Intake.Step.Record
        assertEquals(1, step.payloadReads)
        val store = Store()
        var opened = 0
        val receipt = intake.record(step, { opened++; store }, clock)
        assertEquals(1, opened)
        assertEquals(1, store.closed)
        assertEquals(1, receipt.payloadReads)
        val input = store.inputs.single() as ObservationInput.Observed
        assertEquals(42L, input.receivedAtEpochMs)
        assertEquals(PACKAGE, input.sender.packageName)
        val recorded = receipt.recording as DexcomG7Recording.Recorded
        assertEquals("input.glucose.policy_not_approved", recorded.glucose.code)
    }

    @Test
    fun `a malformed payload from an authenticated sender is stored only as a rejection`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, Source()) {
            DexcomExtrasRead.Read(DexcomG7Payload(ObservedDexcomG7Contract.ACTION, emptyMap()))
        } as DexcomG7Intake.Step.Record
        val store = Store()
        val receipt = intake.record(step, { store }, clock)
        assertTrue(store.inputs.single() is ObservationInput.PayloadRejected)
        assertEquals(UnavailabilityReason.PARSE_FAILED, (receipt.recording as DexcomG7Recording.Recorded).glucose.reason)
    }

    @Test
    fun `a failed write blocks as persistence failed and the store is closed`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, Source()) { DexcomExtrasRead.Unreadable } as DexcomG7Intake.Step.Record
        val store = Store(fail = true)
        val receipt = intake.record(step, { store }, clock)
        assertEquals("input.glucose.persistence_failed", receipt.recording.glucose.code)
        assertEquals(1, store.closed)
    }

    @Test
    fun `store defects propagate unchanged and the store is still closed`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, Source()) { DexcomExtrasRead.Unreadable } as DexcomG7Intake.Step.Record
        val defect = IllegalStateException("synthetic store defect")
        val store = Store(defect = defect)
        val thrown = assertThrows(IllegalStateException::class.java) { intake.record(step, { store }, clock) }
        assertSame(defect, thrown)
        assertEquals(1, store.closed)
    }

    @Test
    fun `no receipt makes glucose available`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val codes = listOf(
            (intake.accept(ACTION, Source(AttributedSenderIdentity("example.spoof", UID))) { error("unused") }
                as DexcomG7Intake.Step.Done).receipt,
            intake.record(intake.accept(ACTION, Source()) { DexcomExtrasRead.Read(payload()) } as DexcomG7Intake.Step.Record,
                { Store() }, clock),
            intake.record(intake.accept(ACTION, Source()) { DexcomExtrasRead.Unreadable } as DexcomG7Intake.Step.Record,
                { Store(fail = true) }, clock),
        ).map {
            when (it) {
                is DexcomReceipt.IgnoredAction -> error("unexpected")
                is DexcomReceipt.SenderRejected -> it.outcome.glucose.code
                is DexcomReceipt.Recorded -> it.recording.glucose.code
            }
        }
        assertEquals(
            listOf("input.glucose.authentication_failed", "input.glucose.policy_not_approved", "input.glucose.persistence_failed"),
            codes,
        )
    }

    @Test
    fun `production identifiers are random and never empty`() {
        val first = AndroidObservationIds.next()
        assertTrue(first.isNotEmpty())
        assertNotEquals(first, AndroidObservationIds.next())
    }

    @Test
    fun `the receiver component state maps to stable codes`() {
        assertEquals(DexcomReceiverComponent.DISABLED_BY_DEFAULT,
            DexcomReceiverComponent.of(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertEquals(DexcomReceiverComponent.ENABLED, DexcomReceiverComponent.of(PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        listOf(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        ).forEach { assertEquals(DexcomReceiverComponent.DISABLED, DexcomReceiverComponent.of(it)) }
        assertEquals(DexcomReceiverComponent.UNKNOWN, DexcomReceiverComponent.of(99))
        assertEquals(
            listOf("dexcom_receiver.component=enabled", "dexcom_receiver.policy=pending"),
            DexcomReceiverStatus(DexcomReceiverComponent.ENABLED, DexcomSenderTrustPolicy.Pending).lines(),
        )
        assertEquals(
            listOf("dexcom_receiver.component=disabled_by_default", "dexcom_receiver.policy=approved"),
            DexcomReceiverStatus(DexcomReceiverComponent.DISABLED_BY_DEFAULT, policy()).lines(),
        )
    }

    private fun payload() = DexcomG7Payload(
        ObservedDexcomG7Contract.ACTION,
        mapOf(
            "sensorType" to PayloadValue.Text("G7"),
            "packageName" to PayloadValue.Text(PACKAGE),
            "glucoseValues" to PayloadValue.Group(
                mapOf(
                    "0" to PayloadValue.Group(
                        mapOf(
                            "glucoseValue" to PayloadValue.Int32(123),
                            "timestamp" to PayloadValue.Int64(1_000),
                            "trendArrow" to PayloadValue.Text("SYNTHETIC_TREND"),
                            "transmitterId" to PayloadValue.Text("SYNTH-TX"),
                            "egvTxTime" to PayloadValue.Int64(77),
                        ),
                    ),
                ),
            ),
        ),
    )

    private class Source(
        private val identity: AttributedSenderIdentity = AttributedSenderIdentity(PACKAGE, UID),
    ) : DexcomSenderEvidenceSource {
        override fun attributedIdentity(): AttributedSenderIdentity = identity
        override fun resolvePackage(packageName: String): SenderPackageResolution =
            SenderPackageResolution.Resolved(ResolvedAndroidPackageIdentity(PACKAGE, UID, 42, setOf(DIGEST)))
    }

    private companion object {
        const val ACTION = "org.bolusai.next.test.SYNTHETIC_G7"
        const val PACKAGE = "example.sender"
        const val UID = 12345
        val DIGEST = "a".repeat(64)
        fun policy() = DexcomSenderTrustPolicy.Approved(PACKAGE, listOf(DexcomSenderReleaseAnchor(42, setOf(DIGEST))))
    }
}
