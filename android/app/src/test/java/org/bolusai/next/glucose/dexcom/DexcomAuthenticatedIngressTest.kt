package org.bolusai.next.glucose.dexcom

import org.bolusai.engine.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DexcomAuthenticatedIngressTest {
    @Test
    fun `every authentication rejection prevents downstream execution and preserves its cause`() {
        val cases = listOf(
            Triple(DexcomSenderTrustPolicy.Pending, Source(), DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED),
            Triple(policy(), Source(identity = AttributedSenderIdentity(null, null)), DexcomSenderAuthenticationFailure.IDENTITY_NOT_SHARED),
            Triple(policy(), Source(identity = AttributedSenderIdentity("example.spoof", UID)), DexcomSenderAuthenticationFailure.PACKAGE_MISMATCH),
            Triple(policy(), Source(resolution = SenderPackageResolution.NotFound), DexcomSenderAuthenticationFailure.PACKAGE_NOT_RESOLVED),
            Triple(policy(), Source(resolution = SenderPackageResolution.AccessDenied), DexcomSenderAuthenticationFailure.PACKAGE_LOOKUP_DENIED),
            Triple(policy(), Source(resolution = SenderPackageResolution.SigningInfoUnavailable), DexcomSenderAuthenticationFailure.SIGNING_INFO_UNAVAILABLE),
            Triple(policy(), Source(resolution = resolved(uid = UID + 1)), DexcomSenderAuthenticationFailure.UID_MISMATCH),
            Triple(policy(), Source(resolution = resolved(version = 43)), DexcomSenderAuthenticationFailure.VERSION_NOT_APPROVED),
            Triple(policy(), Source(resolution = resolved(digest = "b".repeat(64))), DexcomSenderAuthenticationFailure.CERTIFICATE_MISMATCH),
        )
        assertEquals(DexcomSenderAuthenticationFailure.entries.toSet(), cases.map { it.third }.toSet())
        cases.forEach { (policy, source, failure) ->
            val result = DexcomAuthenticatedIngress(policy).receive(source) {
                error("Rejected sender must not reach payload parsing or storage")
            }
            assertEquals(DexcomIngressResult.Rejected(rejected(failure)), result)
            assertEquals(
                if (failure == DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED) emptyList<String>()
                else if (failure in setOf(DexcomSenderAuthenticationFailure.IDENTITY_NOT_SHARED, DexcomSenderAuthenticationFailure.PACKAGE_MISMATCH)) listOf("identity")
                else listOf("identity", "package"),
                source.calls,
            )
        }
    }

    @Test
    fun `authenticated continuation runs once after evidence and can retain a policy block`() {
        val source = Source()
        val result = DexcomAuthenticatedIngress(policy()).receive(source) { sender ->
            assertEquals(listOf("identity", "package"), source.calls)
            source.calls.add("continuation")
            assertEquals(PACKAGE, sender.packageName)
            assertEquals(UID, sender.uid)
            assertEquals(42L, sender.versionCode)
            assertEquals(setOf(DIGEST), sender.signerCertificateSha256Digests)
            // Sender authenticity does not approve the separate clinical contract.
            UnavailabilityReason.POLICY_NOT_APPROVED
        }
        assertEquals(DexcomIngressResult.Handled(UnavailabilityReason.POLICY_NOT_APPROVED), result)
        assertEquals(listOf("identity", "package", "continuation"), source.calls)
    }

    @Test
    fun `successful message never authorizes the following message`() {
        val ingress = DexcomAuthenticatedIngress(policy())
        var handled = 0
        ingress.receive(Source()) { handled++ }
        val result = ingress.receive(Source(resolution = resolved(digest = "b".repeat(64)))) { handled++ }
        assertEquals(1, handled)
        assertEquals(DexcomIngressResult.Rejected(rejected(DexcomSenderAuthenticationFailure.CERTIFICATE_MISMATCH)), result)
    }

    @Test
    fun `evidence defects propagate without invoking the continuation`() {
        val defect = IllegalStateException("synthetic evidence defect")
        val source = object : DexcomSenderEvidenceSource {
            override fun attributedIdentity(): AttributedSenderIdentity = throw defect
            override fun resolvePackage(packageName: String): SenderPackageResolution = error("Unexpected lookup")
        }
        val actual = assertThrows(IllegalStateException::class.java) {
            DexcomAuthenticatedIngress(policy()).receive(source) { error("Unexpected continuation") }
        }
        assertSame(defect, actual)
    }

    @Test
    fun `continuation defects propagate without retry or relabelling as authentication failure`() {
        val defect = IllegalStateException("synthetic downstream defect")
        var attempts = 0
        val actual = assertThrows(IllegalStateException::class.java) {
            DexcomAuthenticatedIngress(policy()).receive(Source()) {
                attempts++
                throw defect
            }
        }
        assertSame(defect, actual)
        assertEquals(1, attempts)
    }

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
            assertEquals(PACKAGE, packageName)
            calls.add("package")
            return resolution
        }
    }

    private companion object {
        const val PACKAGE = "example.sender"
        const val UID = 12345
        val DIGEST = "a".repeat(64)
        fun policy() = DexcomSenderTrustPolicy.Approved(PACKAGE, listOf(DexcomSenderReleaseAnchor(42, setOf(DIGEST))))
        fun resolved(uid: Int = UID, version: Long = 42, digest: String = DIGEST) =
            SenderPackageResolution.Resolved(ResolvedAndroidPackageIdentity(PACKAGE, uid, version, setOf(digest)))
    }
}
