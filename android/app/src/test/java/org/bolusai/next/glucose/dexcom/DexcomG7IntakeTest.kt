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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/** ADR 0022: receiver logic with synthetic senders, payloads and stores only. No real Dexcom data. */
class DexcomG7IntakeTest {
    private class Store(
        private val fail: Boolean = false,
        private val defect: Throwable? = null,
        private val closeDefect: Throwable? = null,
    ) : DexcomObservationStore, Closeable {
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
        override fun close() {
            closed++
            closeDefect?.let { throw it }
        }
    }

    /** Fails if touched: proves that nothing about the sender was looked at. */
    private object Untouchable : DexcomSenderEvidenceSource {
        override fun attributedIdentity(): AttributedSenderIdentity = error("sender must not be authenticated")
        override fun resolvePackage(packageName: String): SenderPackageResolution = error("sender must not be resolved")
    }

    private val clock = EpochClock { 42L }
    private val unread = EpochClock { error("clock must not be read") }

    @Test
    fun `another action is ignored without authenticating or reading`() {
        val step = DexcomG7Intake(ACTION, policy()).accept("example.OTHER", { Untouchable }, unread) { error("must not read") }
        assertEquals(DexcomG7Intake.Step.Done(DexcomReceipt.IgnoredAction("example.OTHER")), step)
        val missing = DexcomG7Intake(ACTION, policy()).accept(null, { Untouchable }, unread) { error("must not read") }
        assertEquals(DexcomG7Intake.Step.Done(DexcomReceipt.IgnoredAction(null)), missing)
    }

    @Test
    fun `the pending production policy rejects every sender before reading extras`() {
        assertSame(DexcomSenderTrustPolicy.Pending, DexcomReceiverConfiguration.policy)
        assertEquals(ObservedDexcomG7Contract.ACTION, DexcomReceiverConfiguration.ACTION)
        val step = DexcomG7Intake(DexcomReceiverConfiguration.ACTION, DexcomReceiverConfiguration.policy)
            .accept(DexcomReceiverConfiguration.ACTION, { Untouchable }, unread) { error("must not read") }
        val receipt = (step as DexcomG7Intake.Step.Done).receipt as DexcomReceipt.SenderRejected
        assertEquals(0, receipt.payloadReads)
        assertEquals(DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED, receipt.outcome.authentication.failure)
        assertEquals("input.glucose.policy_not_approved", receipt.outcome.glucose.code)
    }

    @Test
    fun `a rejected sender never reaches the store`() {
        val step = DexcomG7Intake(ACTION, policy()).accept(ACTION, { Source(AttributedSenderIdentity(null, null)) }, unread) {
            error("must not read")
        }
        val receipt = (step as DexcomG7Intake.Step.Done).receipt as DexcomReceipt.SenderRejected
        assertEquals(0, receipt.payloadReads)
        assertEquals(DexcomSenderAuthenticationFailure.IDENTITY_NOT_SHARED, receipt.outcome.authentication.failure)
    }

    @Test
    fun `an authenticated message is read once, recorded once and stays blocked`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Read(payload()) } as DexcomG7Intake.Step.Record
        assertEquals(1, step.payloadReads)
        val store = Store()
        var opened = 0
        val receipt = intake.record(step) { opened++; store }.recorded()
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
    fun `the reception instant is captured on accept, not when a queued write runs`() {
        var now = 1_000L
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, { Source() }, EpochClock { now }) { DexcomExtrasRead.Read(payload()) }
            as DexcomG7Intake.Step.Record
        assertEquals(1_000L, step.receivedAtEpochMs)
        now = 9_000L // a delayed serial writer runs later
        val store = Store()
        intake.record(step) { store }
        assertEquals(1_000L, store.inputs.single().receivedAtEpochMs)
    }

    @Test
    fun `a malformed payload from an authenticated sender is stored only as a rejection`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, { Source() }, clock) {
            DexcomExtrasRead.Read(DexcomG7Payload(ObservedDexcomG7Contract.ACTION, emptyMap()))
        } as DexcomG7Intake.Step.Record
        val store = Store()
        val receipt = intake.record(step) { store }.recorded()
        assertTrue(store.inputs.single() is ObservationInput.PayloadRejected)
        assertEquals(UnavailabilityReason.PARSE_FAILED, (receipt.recording as DexcomG7Recording.Recorded).glucose.reason)
    }

    @Test
    fun `a failed write blocks as persistence failed and the store is closed`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Unreadable } as DexcomG7Intake.Step.Record
        val store = Store(fail = true)
        val receipt = intake.record(step) { store }.recorded()
        assertEquals("input.glucose.persistence_failed", receipt.recording.glucose.code)
        assertEquals(1, store.closed)
    }

    @Test
    fun `a store that cannot be opened is a storage failure, not an uncaught exception`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Read(payload()) } as DexcomG7Intake.Step.Record
        // Same exception types the SQLite store and the instrumentation barrier throw; their text must not leak.
        listOf(
            IllegalStateException("dexcom_storage.real_database_denied_in_instrumentation"),
            RuntimeException("dexcom_observation.unsupported_schema: inventory"),
            java.io.IOException("synthetic open failure"),
        ).forEach { defect ->
            val receipt = intake.record<Store>(step) { throw defect }
            assertEquals(DexcomReceipt.StorageFailed(DexcomStorageFailure.OPEN_FAILED, 1), receipt)
            assertEquals("input.glucose.persistence_failed", (receipt as DexcomReceipt.StorageFailed).glucose.code)
        }
    }

    @Test
    fun `a record that throws is a storage failure and the store is still closed`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Unreadable } as DexcomG7Intake.Step.Record
        val store = Store(defect = IllegalStateException("synthetic store defect"))
        val receipt = intake.record(step) { store }
        assertEquals(DexcomReceipt.StorageFailed(DexcomStorageFailure.RECORD_FAILED, 1), receipt)
        assertEquals(1, store.closed)
    }

    @Test
    fun `the first failing stage wins and a failed close is never reported as recorded`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Unreadable } as DexcomG7Intake.Step.Record
        val both = Store(defect = IllegalStateException("record"), closeDefect = java.io.IOException("close"))
        assertEquals(DexcomReceipt.StorageFailed(DexcomStorageFailure.RECORD_FAILED, 1), intake.record(step) { both })
        assertEquals(1, both.closed)
        val closeOnly = Store(closeDefect = java.io.IOException("close"))
        assertEquals(DexcomReceipt.StorageFailed(DexcomStorageFailure.CLOSE_FAILED, 1), intake.record(step) { closeOnly })
        assertEquals(1, closeOnly.inputs.size)
        assertEquals(1, closeOnly.closed)
    }

    @Test
    fun `storage failure codes are stable`() {
        assertEquals(
            listOf("dexcom_receiver.store_open_failed", "dexcom_receiver.store_record_failed", "dexcom_receiver.store_close_failed"),
            DexcomStorageFailure.entries.map { it.code },
        )
    }

    @Test
    fun `jvm errors are not swallowed`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val step = intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Unreadable } as DexcomG7Intake.Step.Record
        val error = AssertionError("synthetic error")
        val store = Store(defect = error)
        assertSame(error, assertThrows(AssertionError::class.java) { intake.record(step) { store } })
    }

    @Test
    fun `an authentication that throws reads no extras, opens no store and reads no clock`() {
        val secret = "synthetic secret text"
        listOf<() -> DexcomSenderEvidenceSource>(
            { throw IllegalStateException(secret) },
            { Throwing(identity = RuntimeException(secret)) },
            { Throwing(resolution = RuntimeException(secret)) }, // e.g. the package manager died
        ).forEach { sources ->
            val step = DexcomG7Intake(ACTION, policy()) { _, _ -> error("parser must not run") }
                .accept(ACTION, sources, unread) { error("extras must not be read") }
            // Done is final: there is no Record step, so the store cannot be opened.
            val receipt = (step as DexcomG7Intake.Step.Done).receipt
            assertEquals(DexcomReceipt.IntakeFailed(DexcomIntakeFailure.AUTHENTICATION, 0), receipt)
            assertEquals("input.glucose.authentication_failed", (receipt as DexcomReceipt.IntakeFailed).glucose.code)
            assertFalse(receipt.toString().contains(secret))
        }
    }

    @Test
    fun `the pending policy invokes the source factory once and never queries the source`() {
        val source = Counting()
        var created = 0
        val step = DexcomG7Intake(DexcomReceiverConfiguration.ACTION, DexcomReceiverConfiguration.policy)
            .accept(DexcomReceiverConfiguration.ACTION, { created++; source }, unread) { error("must not read") }
        val receipt = (step as DexcomG7Intake.Step.Done).receipt as DexcomReceipt.SenderRejected
        assertEquals(1, created)
        assertEquals(0, source.identityCalls)
        assertEquals(0, source.resolveCalls)
        assertEquals(0, receipt.payloadReads)
        assertEquals(DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED, receipt.outcome.authentication.failure)
    }

    @Test
    fun `another action never invokes the source factory`() {
        val step = DexcomG7Intake(ACTION, policy()).accept("example.OTHER", { error("factory must not run") }, unread) {
            error("must not read")
        }
        assertEquals(DexcomG7Intake.Step.Done(DexcomReceipt.IgnoredAction("example.OTHER")), step)
    }

    @Test
    fun `extras that throw fail the extras stage after one read and never reach the parser`() {
        val secret = "synthetic secret text"
        var parsed = 0
        val step = DexcomG7Intake(ACTION, policy()) { _, _ -> parsed++; error("parser must not run") }
            .accept(ACTION, { Source() }, unread) { throw IllegalStateException(secret) }
        val receipt = (step as DexcomG7Intake.Step.Done).receipt
        assertEquals(DexcomReceipt.IntakeFailed(DexcomIntakeFailure.EXTRAS, 1), receipt)
        assertEquals("input.glucose.parse_failed", (receipt as DexcomReceipt.IntakeFailed).glucose.code)
        assertEquals(0, parsed)
        assertFalse(receipt.toString().contains(secret))
    }

    @Test
    fun `a parser that throws fails the parser stage, never the extras stage`() {
        val secret = "synthetic secret text"
        var reads = 0
        // Same exception type as the extras test: only the boundary decides the stage.
        val step = DexcomG7Intake(ACTION, policy()) { _, _ -> throw IllegalStateException(secret) }
            .accept(ACTION, { Source() }, unread) { reads++; DexcomExtrasRead.Read(payload()) }
        val receipt = (step as DexcomG7Intake.Step.Done).receipt
        assertEquals(DexcomReceipt.IntakeFailed(DexcomIntakeFailure.PARSER, 1), receipt)
        assertEquals("input.glucose.parse_failed", (receipt as DexcomReceipt.IntakeFailed).glucose.code)
        assertEquals(1, reads)
        assertFalse(receipt.toString().contains(secret))
    }

    @Test
    fun `an unreadable payload is still recorded as unreadable, not as an intake failure`() {
        // ADR 0019, T3 unchanged: the extras view reports unparcel failures as Unreadable; accept never relabels.
        val step = DexcomG7Intake(ACTION, policy()).accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Unreadable }
        val record = step as DexcomG7Intake.Step.Record
        assertTrue(record.outcome is DexcomG7ReceptionOutcome.PayloadUnreadable)
        assertEquals(1, record.payloadReads)
    }

    @Test
    fun `jvm errors in the synchronous stages are not swallowed`() {
        val error = AssertionError("synthetic error")
        val stages = listOf(
            { DexcomG7Intake(ACTION, policy()).accept(ACTION, { throw error }, unread) { error("unused") } },
            { DexcomG7Intake(ACTION, policy()).accept(ACTION, { Throwing(identity = error) }, unread) { error("unused") } },
            { DexcomG7Intake(ACTION, policy()).accept(ACTION, { Source() }, unread) { throw error } },
            {
                DexcomG7Intake(ACTION, policy()) { _, _ -> throw error }
                    .accept(ACTION, { Source() }, unread) { DexcomExtrasRead.Read(payload()) }
            },
        )
        stages.forEach { stage -> assertSame(error, assertThrows(AssertionError::class.java) { stage() }) }
    }

    @Test
    fun `intake failure codes are stable`() {
        assertEquals(
            listOf(
                "dexcom_receiver.intake_authentication_failed" to UnavailabilityReason.AUTHENTICATION_FAILED,
                "dexcom_receiver.intake_extras_failed" to UnavailabilityReason.PARSE_FAILED,
                "dexcom_receiver.intake_parser_failed" to UnavailabilityReason.PARSE_FAILED,
            ),
            DexcomIntakeFailure.entries.map { it.code to it.reason },
        )
    }

    @Test
    fun `no receipt makes glucose available`() {
        val intake = DexcomG7Intake(ACTION, policy())
        val codes = listOf(
            (intake.accept(ACTION, { Source(AttributedSenderIdentity("example.spoof", UID)) }, unread) { error("unused") }
                as DexcomG7Intake.Step.Done).receipt,
            intake.record(intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Read(payload()) } as DexcomG7Intake.Step.Record) {
                Store()
            },
            intake.record(intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Unreadable } as DexcomG7Intake.Step.Record) {
                Store(fail = true)
            },
            intake.record<Store>(intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Unreadable }
                as DexcomG7Intake.Step.Record) { error("synthetic open failure") },
            intake.record(intake.accept(ACTION, { Source() }, clock) { DexcomExtrasRead.Unreadable } as DexcomG7Intake.Step.Record) {
                Store(defect = IllegalStateException("synthetic"))
            },
            (intake.accept(ACTION, { error("synthetic") }, unread) { error("unused") } as DexcomG7Intake.Step.Done).receipt,
            (intake.accept(ACTION, { Source() }, unread) { error("synthetic") } as DexcomG7Intake.Step.Done).receipt,
            (DexcomG7Intake(ACTION, policy()) { _, _ -> error("synthetic") }
                .accept(ACTION, { Source() }, unread) { DexcomExtrasRead.Read(payload()) } as DexcomG7Intake.Step.Done).receipt,
        ).map {
            when (it) {
                is DexcomReceipt.IgnoredAction -> error("unexpected")
                is DexcomReceipt.SenderRejected -> it.outcome.glucose.code
                is DexcomReceipt.Recorded -> it.recording.glucose.code
                is DexcomReceipt.StorageFailed -> it.glucose.code
                is DexcomReceipt.IntakeFailed -> it.glucose.code
            }
        }
        assertEquals(
            listOf(
                "input.glucose.authentication_failed",
                "input.glucose.policy_not_approved",
                "input.glucose.persistence_failed",
                "input.glucose.persistence_failed",
                "input.glucose.persistence_failed",
                "input.glucose.authentication_failed",
                "input.glucose.parse_failed",
                "input.glucose.parse_failed",
            ),
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

    private fun DexcomReceipt.recorded() = this as DexcomReceipt.Recorded

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

    /** Throws from the Android-backed calls, like a PackageManager that died mid-lookup. */
    private class Throwing(
        private val identity: Throwable? = null,
        private val resolution: Throwable? = null,
    ) : DexcomSenderEvidenceSource {
        override fun attributedIdentity(): AttributedSenderIdentity {
            identity?.let { throw it }
            return AttributedSenderIdentity(PACKAGE, UID)
        }
        override fun resolvePackage(packageName: String): SenderPackageResolution {
            resolution?.let { throw it }
            return SenderPackageResolution.Resolved(ResolvedAndroidPackageIdentity(PACKAGE, UID, 42, setOf(DIGEST)))
        }
    }

    /** Counts every query: with the Pending policy none may happen. */
    private class Counting : DexcomSenderEvidenceSource {
        var identityCalls = 0
        var resolveCalls = 0
        override fun attributedIdentity(): AttributedSenderIdentity {
            identityCalls++
            return AttributedSenderIdentity(PACKAGE, UID)
        }
        override fun resolvePackage(packageName: String): SenderPackageResolution {
            resolveCalls++
            return SenderPackageResolution.NotFound
        }
    }

    private companion object {
        const val ACTION = "org.bolusai.next.test.SYNTHETIC_G7"
        const val PACKAGE = "example.sender"
        const val UID = 12345
        val DIGEST = "a".repeat(64)
        fun policy() = DexcomSenderTrustPolicy.Approved(PACKAGE, listOf(DexcomSenderReleaseAnchor(42, setOf(DIGEST))))
    }
}
