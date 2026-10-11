package org.bolusai.next.glucose.dexcom

import org.bolusai.dexcomg7.DexcomG7Payload
import org.bolusai.dexcomg7.DexcomG7PayloadRejection
import org.bolusai.dexcomg7.DexcomG7RejectionCode
import org.bolusai.dexcomg7.ObservedDexcomG7Contract
import org.bolusai.dexcomg7.PayloadValue
import org.bolusai.engine.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic senders and payloads only (ADR 0019). No receiver, storage or real Dexcom data. */
class DexcomG7ReceptionTest {
    @Test
    fun `every sender rejection blocks before reading the payload and keeps its reason`() {
        val cases = listOf(
            DexcomSenderTrustPolicy.Pending to Source(),
            policy() to Source(identity = AttributedSenderIdentity(null, null)),
            policy() to Source(identity = AttributedSenderIdentity("example.spoof", UID)),
            policy() to Source(resolution = SenderPackageResolution.NotFound),
            policy() to Source(resolution = resolved(digest = "b".repeat(64))),
        )
        for ((policy, source) in cases) {
            val outcome = DexcomG7Reception(policy).receive(source) { error("Payload must not be read before authentication") }
            val rejected = (outcome as DexcomG7ReceptionOutcome.SenderRejected).authentication
            assertEquals(rejected.unavailabilityReason, outcome.glucose.reason)
            assertEquals("glucose", outcome.glucose.input.code)
        }
        val pending = DexcomG7Reception(DexcomSenderTrustPolicy.Pending).receive(Source()) { error("unexpected") }
        assertEquals("input.glucose.policy_not_approved", pending.glucose.code)
        val spoofed = DexcomG7Reception(policy()).receive(Source(identity = AttributedSenderIdentity("example.spoof", UID))) {
            error("unexpected")
        }
        assertEquals("input.glucose.authentication_failed", spoofed.glucose.code)
    }

    @Test
    fun `a valid observed message still blocks glucose as policy not approved`() {
        val source = Source()
        var reads = 0
        val outcome = DexcomG7Reception(policy()).receive(source) {
            assertEquals(listOf("identity", "package"), source.calls)
            reads++
            DexcomExtrasRead.Read(payload())
        }
        assertEquals(1, reads)
        val observed = outcome as DexcomG7ReceptionOutcome.Observed
        assertEquals(PACKAGE, observed.message.authenticatedPackage)
        assertEquals(authenticated(), observed.sender)
        assertEquals(1, observed.message.readings.size)
        assertEquals("input.glucose.policy_not_approved", outcome.glucose.code)
    }

    @Test
    fun `the parser compares the package claim with the authenticated sender`() {
        val outcome = DexcomG7Reception(policy()).receive(Source()) {
            DexcomExtrasRead.Read(payload(packageClaim = "example.other"))
        }
        assertEquals(
            DexcomG7ReceptionOutcome.PayloadRejected(
                authenticated(),
                DexcomG7PayloadRejection(DexcomG7RejectionCode.PACKAGE_CLAIM_MISMATCH, ObservedDexcomG7Contract.PACKAGE_NAME),
            ),
            outcome,
        )
        assertEquals("input.glucose.parse_failed", outcome.glucose.code)
    }

    @Test
    fun `unreadable and rejected payloads both block as parse failed`() {
        val unreadable = DexcomG7Reception(policy()).receive(Source()) { DexcomExtrasRead.Unreadable }
        assertEquals(DexcomG7ReceptionOutcome.PayloadUnreadable(authenticated()), unreadable)
        assertEquals(UnavailabilityReason.PARSE_FAILED, unreadable.glucose.reason)

        val rejected = DexcomG7Reception(policy()).receive(Source()) {
            DexcomExtrasRead.Read(DexcomG7Payload("synthetic.OTHER", emptyMap()))
        }
        assertEquals(
            DexcomG7ReceptionOutcome.PayloadRejected(authenticated(), DexcomG7PayloadRejection(DexcomG7RejectionCode.WRONG_ACTION)),
            rejected,
        )
        assertEquals(UnavailabilityReason.PARSE_FAILED, rejected.glucose.reason)
    }

    @Test
    fun `no outcome can make glucose available`() {
        val outcomes = listOf(
            DexcomG7Reception(DexcomSenderTrustPolicy.Pending).receive(Source()) { error("unexpected") },
            DexcomG7Reception(policy()).receive(Source()) { DexcomExtrasRead.Unreadable },
            DexcomG7Reception(policy()).receive(Source()) { DexcomExtrasRead.Read(payload(packageClaim = "x.y")) },
            DexcomG7Reception(policy()).receive(Source()) { DexcomExtrasRead.Read(payload()) },
        )
        assertTrue(outcomes.all { it.glucose.input.code == "glucose" })
        assertEquals(
            setOf(UnavailabilityReason.POLICY_NOT_APPROVED, UnavailabilityReason.PARSE_FAILED),
            outcomes.map { it.glucose.reason }.toSet(),
        )
    }

    @Test
    fun `each message authenticates afresh`() {
        val reception = DexcomG7Reception(policy())
        var reads = 0
        reception.receive(Source()) { reads++; DexcomExtrasRead.Read(payload()) }
        val second = reception.receive(Source(resolution = resolved(uid = UID + 1))) { reads++; DexcomExtrasRead.Unreadable }
        assertEquals(1, reads)
        assertEquals("input.glucose.authentication_failed", second.glucose.code)
    }

    @Test
    fun `payload reader defects propagate without relabelling`() {
        val defect = IllegalStateException("synthetic reader defect")
        val actual = assertThrows(IllegalStateException::class.java) {
            DexcomG7Reception(policy()).receive(Source()) { throw defect }
        }
        assertSame(defect, actual)
    }

    private fun payload(packageClaim: String = PACKAGE) = DexcomG7Payload(
        ObservedDexcomG7Contract.ACTION,
        mapOf(
            "sensorType" to PayloadValue.Text("G7"),
            "packageName" to PayloadValue.Text(packageClaim),
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
        private val resolution: SenderPackageResolution = resolved(),
    ) : DexcomSenderEvidenceSource {
        val calls = mutableListOf<String>()
        override fun attributedIdentity(): AttributedSenderIdentity {
            calls.add("identity")
            return identity
        }
        override fun resolvePackage(packageName: String): SenderPackageResolution {
            calls.add("package")
            return resolution
        }
    }

    private companion object {
        const val PACKAGE = "example.sender"
        const val UID = 12345
        val DIGEST = "a".repeat(64)
        fun policy() = DexcomSenderTrustPolicy.Approved(PACKAGE, listOf(DexcomSenderReleaseAnchor(42, setOf(DIGEST))))
        fun authenticated() = DexcomSenderAuthenticationResult.Authenticated(PACKAGE, UID, 42, setOf(DIGEST))
        fun resolved(uid: Int = UID, version: Long = 42, digest: String = DIGEST) =
            SenderPackageResolution.Resolved(ResolvedAndroidPackageIdentity(PACKAGE, uid, version, setOf(digest)))
    }
}
