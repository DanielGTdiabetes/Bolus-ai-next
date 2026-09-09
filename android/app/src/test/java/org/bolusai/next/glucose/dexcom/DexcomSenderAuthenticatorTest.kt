package org.bolusai.next.glucose.dexcom

import org.bolusai.engine.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomSenderAuthenticatorTest {
    @Test
    fun `pending policy rejects without trusting supplied identity`() {
        val result = DexcomSenderAuthenticator(DexcomSenderTrustPolicy.Pending)
            .authenticate(approvedEvidence())

        assertRejected(
            result,
            DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED,
            UnavailabilityReason.POLICY_NOT_APPROVED,
        )
    }

    @Test
    fun `identity must include both system attributed package and uid`() {
        val missingPackage = approvedEvidence().copy(sentFromPackage = null)
        val missingUid = approvedEvidence().copy(sentFromUid = null)

        listOf(missingPackage, missingUid).forEach { evidence ->
            assertRejected(
                authenticator().authenticate(evidence),
                DexcomSenderAuthenticationFailure.IDENTITY_NOT_SHARED,
                UnavailabilityReason.AUTHENTICATION_FAILED,
            )
        }
    }

    @Test
    fun `copied action and extras cannot replace the expected sender package`() {
        val evidence = approvedEvidence().copy(sentFromPackage = "example.spoof")

        assertRejected(
            authenticator().authenticate(evidence),
            DexcomSenderAuthenticationFailure.PACKAGE_MISMATCH,
            UnavailabilityReason.AUTHENTICATION_FAILED,
        )
    }

    @Test
    fun `sender package must resolve through PackageManager`() {
        val evidence = approvedEvidence().copy(resolvedPackage = null)

        assertRejected(
            authenticator().authenticate(evidence),
            DexcomSenderAuthenticationFailure.PACKAGE_NOT_RESOLVED,
            UnavailabilityReason.AUTHENTICATION_FAILED,
        )
    }

    @Test
    fun `resolved package must belong to the system attributed uid`() {
        val evidence = approvedEvidence().copy(
            resolvedPackage = resolvedIdentity(uid = APPROVED_UID + 1),
        )

        assertRejected(
            authenticator().authenticate(evidence),
            DexcomSenderAuthenticationFailure.UID_MISMATCH,
            UnavailabilityReason.AUTHENTICATION_FAILED,
        )
    }

    @Test
    fun `version code must identify an explicitly approved release`() {
        val evidence = approvedEvidence().copy(
            resolvedPackage = resolvedIdentity(versionCode = APPROVED_VERSION + 1),
        )

        assertRejected(
            authenticator().authenticate(evidence),
            DexcomSenderAuthenticationFailure.VERSION_NOT_APPROVED,
            UnavailabilityReason.AUTHENTICATION_FAILED,
        )
    }

    @Test
    fun `complete signer set must match the approved release`() {
        val evidence = approvedEvidence().copy(
            resolvedPackage = resolvedIdentity(setOf(OTHER_CERTIFICATE)),
        )

        assertRejected(
            authenticator().authenticate(evidence),
            DexcomSenderAuthenticationFailure.CERTIFICATE_MISMATCH,
            UnavailabilityReason.AUTHENTICATION_FAILED,
        )
    }

    @Test
    fun `version and certificate anchors cannot be recombined across releases`() {
        val policy = DexcomSenderTrustPolicy.Approved(
            packageName = APPROVED_PACKAGE,
            releaseAnchors = listOf(
                releaseAnchor(APPROVED_VERSION, APPROVED_CERTIFICATE),
                releaseAnchor(APPROVED_VERSION + 1, OTHER_CERTIFICATE),
            ),
        )
        val recombinedEvidence = approvedEvidence().copy(
            resolvedPackage = resolvedIdentity(
                signerCertificateSha256Digests = setOf(OTHER_CERTIFICATE),
                versionCode = APPROVED_VERSION,
            ),
        )

        assertRejected(
            DexcomSenderAuthenticator(policy).authenticate(recombinedEvidence),
            DexcomSenderAuthenticationFailure.CERTIFICATE_MISMATCH,
            UnavailabilityReason.AUTHENTICATION_FAILED,
        )
    }

    @Test
    fun `matching attributed and resolved identity authenticates without glucose data`() {
        val result = authenticator().authenticate(approvedEvidence())

        assertTrue(result is DexcomSenderAuthenticationResult.Authenticated)
        result as DexcomSenderAuthenticationResult.Authenticated
        assertEquals(APPROVED_PACKAGE, result.packageName)
        assertEquals(APPROVED_UID, result.uid)
        assertEquals(APPROVED_VERSION, result.versionCode)
        assertEquals(setOf(APPROVED_CERTIFICATE), result.signerCertificateSha256Digests)
    }

    @Test
    fun `approved policy snapshots and canonicalizes its trust anchors`() {
        val mutableCertificates = mutableSetOf(APPROVED_CERTIFICATE.uppercase())
        val mutableAnchors = mutableListOf(
            DexcomSenderReleaseAnchor(APPROVED_VERSION, mutableCertificates),
        )
        val policy = DexcomSenderTrustPolicy.Approved(
            packageName = APPROVED_PACKAGE,
            releaseAnchors = mutableAnchors,
        )

        mutableCertificates += OTHER_CERTIFICATE
        mutableAnchors += releaseAnchor(APPROVED_VERSION + 1, OTHER_CERTIFICATE)

        assertEquals(1, policy.releaseAnchors.size)
        assertEquals(APPROVED_VERSION, policy.releaseAnchors.single().versionCode)
        assertEquals(
            setOf(APPROVED_CERTIFICATE),
            policy.releaseAnchors.single().signerCertificateSha256Digests,
        )
    }

    @Test
    fun `approved policy rejects missing or malformed trust anchors`() {
        assertThrows(IllegalArgumentException::class.java) {
            DexcomSenderTrustPolicy.Approved(APPROVED_PACKAGE, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DexcomSenderReleaseAnchor(APPROVED_VERSION, setOf("not-a-digest"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DexcomSenderReleaseAnchor(APPROVED_VERSION, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DexcomSenderReleaseAnchor(0, setOf(APPROVED_CERTIFICATE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DexcomSenderTrustPolicy.Approved(
                " ",
                listOf(releaseAnchor(APPROVED_VERSION, APPROVED_CERTIFICATE)),
            )
        }
    }

    private fun authenticator(): DexcomSenderAuthenticator =
        DexcomSenderAuthenticator(
            DexcomSenderTrustPolicy.Approved(
                packageName = APPROVED_PACKAGE,
                releaseAnchors = listOf(
                    releaseAnchor(APPROVED_VERSION, APPROVED_CERTIFICATE),
                ),
            )
        )

    private fun approvedEvidence(): DexcomBroadcastSenderEvidence =
        DexcomBroadcastSenderEvidence(
            sentFromPackage = APPROVED_PACKAGE,
            sentFromUid = APPROVED_UID,
            resolvedPackage = resolvedIdentity(),
        )

    private fun resolvedIdentity(
        signerCertificateSha256Digests: Set<String> = setOf(APPROVED_CERTIFICATE),
        uid: Int = APPROVED_UID,
        versionCode: Long = APPROVED_VERSION,
    ): ResolvedAndroidPackageIdentity =
        ResolvedAndroidPackageIdentity(
            packageName = APPROVED_PACKAGE,
            uid = uid,
            versionCode = versionCode,
            signerCertificateSha256Digests = signerCertificateSha256Digests,
        )

    private fun assertRejected(
        result: DexcomSenderAuthenticationResult,
        expectedFailure: DexcomSenderAuthenticationFailure,
        expectedUnavailabilityReason: UnavailabilityReason,
    ) {
        assertTrue(result is DexcomSenderAuthenticationResult.Rejected)
        result as DexcomSenderAuthenticationResult.Rejected
        assertEquals(expectedFailure, result.failure)
        assertEquals(expectedUnavailabilityReason, result.unavailabilityReason)
    }

    private fun releaseAnchor(
        versionCode: Long,
        certificate: String,
    ): DexcomSenderReleaseAnchor =
        DexcomSenderReleaseAnchor(versionCode, setOf(certificate))

    private companion object {
        const val APPROVED_PACKAGE = "example.approved.dexcom"
        const val APPROVED_UID = 12_345
        const val APPROVED_VERSION = 42L
        const val APPROVED_CERTIFICATE =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val OTHER_CERTIFICATE =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
