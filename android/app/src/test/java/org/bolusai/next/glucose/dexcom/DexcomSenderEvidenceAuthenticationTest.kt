package org.bolusai.next.glucose.dexcom

import org.bolusai.engine.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomSenderEvidenceAuthenticationTest {
    @Test
    fun `pending policy never accesses platform evidence`() {
        val source = Source()
        val result = DexcomSenderEvidenceAuthentication(DexcomSenderTrustPolicy.Pending)
            .authenticate(source)
        assertFailure(result, DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED)
        assertEquals(0, source.identityReads)
        assertEquals(0, source.packageReads)
    }

    @Test
    fun `missing or unexpected attributed identity never queries installed packages`() {
        val identities = listOf(
            AttributedSenderIdentity(null, null),
            AttributedSenderIdentity(PACKAGE, -1),
            AttributedSenderIdentity(null, UID),
            AttributedSenderIdentity(PACKAGE, null),
            AttributedSenderIdentity("example.spoof", UID),
        )
        identities.forEach { identity ->
            val source = Source(identity)
            assertTrue(boundary().authenticate(source) is DexcomSenderAuthenticationResult.Rejected)
            assertEquals(1, source.identityReads)
            assertEquals(0, source.packageReads)
        }
    }

    @Test
    fun `lookup failures preserve specific causes`() {
        val failures = mapOf(
            SenderPackageResolution.NotFound to DexcomSenderAuthenticationFailure.PACKAGE_NOT_RESOLVED,
            SenderPackageResolution.AccessDenied to DexcomSenderAuthenticationFailure.PACKAGE_LOOKUP_DENIED,
            SenderPackageResolution.SigningInfoUnavailable to DexcomSenderAuthenticationFailure.SIGNING_INFO_UNAVAILABLE,
        )
        failures.forEach { (resolution, expected) ->
            val result = boundary().authenticate(Source(resolution = resolution))
            assertFailure(result, expected)
            assertEquals(
                UnavailabilityReason.AUTHENTICATION_FAILED,
                (result as DexcomSenderAuthenticationResult.Rejected).unavailabilityReason,
            )
        }
    }

    @Test
    fun `resolved UID version and complete signer set still pass through verifier`() {
        listOf(
            resolved(uid = UID + 1) to DexcomSenderAuthenticationFailure.UID_MISMATCH,
            resolved(version = 43) to DexcomSenderAuthenticationFailure.VERSION_NOT_APPROVED,
            resolved(signers = setOf("b".repeat(64))) to DexcomSenderAuthenticationFailure.CERTIFICATE_MISMATCH,
            resolved(signers = setOf(DIGEST, "b".repeat(64))) to DexcomSenderAuthenticationFailure.CERTIFICATE_MISMATCH,
        ).forEach { (identity, expected) ->
            assertFailure(boundary().authenticate(Source(resolution = SenderPackageResolution.Resolved(identity))), expected)
        }
    }

    @Test
    fun `approved evidence authenticates using only the attributed package`() {
        val source = Source()
        assertTrue(boundary().authenticate(source) is DexcomSenderAuthenticationResult.Authenticated)
        assertEquals(PACKAGE, source.requestedPackage)
        assertEquals(1, source.packageReads)
    }

    @Test
    fun `unexpected platform defects are not relabelled as missing data`() {
        val source = object : DexcomSenderEvidenceSource {
            override fun attributedIdentity() = AttributedSenderIdentity(PACKAGE, UID)
            override fun resolvePackage(packageName: String): SenderPackageResolution = error("fixture defect")
        }
        assertThrows(IllegalStateException::class.java) { boundary().authenticate(source) }
    }

    private class Source(
        private val identity: AttributedSenderIdentity = AttributedSenderIdentity(PACKAGE, UID),
        private val resolution: SenderPackageResolution = SenderPackageResolution.Resolved(resolved()),
    ) : DexcomSenderEvidenceSource {
        var identityReads = 0
        var packageReads = 0
        var requestedPackage: String? = null
        override fun attributedIdentity(): AttributedSenderIdentity {
            identityReads++
            return identity
        }
        override fun resolvePackage(packageName: String): SenderPackageResolution {
            packageReads++
            requestedPackage = packageName
            return resolution
        }
    }

    private fun boundary() = DexcomSenderEvidenceAuthentication(
        DexcomSenderTrustPolicy.Approved(PACKAGE, listOf(DexcomSenderReleaseAnchor(42, setOf(DIGEST)))),
    )

    private fun assertFailure(result: DexcomSenderAuthenticationResult, expected: DexcomSenderAuthenticationFailure) {
        assertTrue(result is DexcomSenderAuthenticationResult.Rejected)
        assertEquals(expected, (result as DexcomSenderAuthenticationResult.Rejected).failure)
    }

    private companion object {
        const val PACKAGE = "example.sender"
        const val UID = 12345
        val DIGEST = "a".repeat(64)
        fun resolved(uid: Int = UID, version: Long = 42, signers: Set<String> = setOf(DIGEST)) =
            ResolvedAndroidPackageIdentity(PACKAGE, uid, version, signers)
    }
}
