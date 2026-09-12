package org.bolusai.next.glucose.dexcom

import org.bolusai.engine.UnavailabilityReason

/**
 * Trust anchors for one or more explicitly approved producer releases.
 *
 * No production policy is instantiated until the owner approves a reproducible
 * producer release. Collections are copied so later caller mutation cannot
 * widen the approved release set.
 */
internal sealed interface DexcomSenderTrustPolicy {
    data object Pending : DexcomSenderTrustPolicy

    class Approved(
        packageName: String,
        releaseAnchors: List<DexcomSenderReleaseAnchor>,
    ) : DexcomSenderTrustPolicy {
        val packageName: String = packageName.also {
            require(it.isNotBlank() && it == it.trim()) {
                "dexcom_sender_policy.invalid_package_name"
            }
        }
        val releaseAnchors: List<DexcomSenderReleaseAnchor> = releaseAnchors.map {
            DexcomSenderReleaseAnchor(
                versionCode = it.versionCode,
                signerCertificateSha256Digests = it.signerCertificateSha256Digests,
            )
        }.also {
            require(it.isNotEmpty()) {
                "dexcom_sender_policy.missing_release_anchor"
            }
        }
    }
}

/** Keeps version and signer anchors paired so trust cannot widen across releases. */
internal class DexcomSenderReleaseAnchor(
    val versionCode: Long,
    signerCertificateSha256Digests: Set<String>,
) {
    init {
        require(versionCode > 0L) {
            "dexcom_sender_policy.invalid_version_code"
        }
    }

    val signerCertificateSha256Digests: Set<String> =
        signerCertificateSha256Digests.map(::canonicalSha256Digest).toSet().also {
            require(it.isNotEmpty()) {
                "dexcom_sender_policy.missing_certificate_digest"
            }
        }
}

/** Evidence obtained from Android before any Dexcom payload is parsed. */
internal data class DexcomBroadcastSenderEvidence(
    val sentFromPackage: String?,
    val sentFromUid: Int?,
    val resolvedPackage: ResolvedAndroidPackageIdentity?,
)

/** PackageManager evidence for the system-attributed sender package. */
internal class ResolvedAndroidPackageIdentity(
    val packageName: String,
    val uid: Int,
    val versionCode: Long,
    signerCertificateSha256Digests: Set<String>,
) {
    val signerCertificateSha256Digests: Set<String> =
        signerCertificateSha256Digests.map(::canonicalSha256Digest).toSet()
}

internal enum class DexcomSenderAuthenticationFailure {
    POLICY_NOT_APPROVED,
    IDENTITY_NOT_SHARED,
    PACKAGE_NOT_RESOLVED,
    PACKAGE_LOOKUP_DENIED,
    SIGNING_INFO_UNAVAILABLE,
    PACKAGE_MISMATCH,
    UID_MISMATCH,
    VERSION_NOT_APPROVED,
    CERTIFICATE_MISMATCH,
}

internal sealed interface DexcomSenderAuthenticationResult {
    data class Rejected(
        val failure: DexcomSenderAuthenticationFailure,
        val unavailabilityReason: UnavailabilityReason,
    ) : DexcomSenderAuthenticationResult

    data class Authenticated(
        val packageName: String,
        val uid: Int,
        val versionCode: Long,
        val signerCertificateSha256Digests: Set<String>,
    ) : DexcomSenderAuthenticationResult
}

/**
 * Performs only sender authentication. It does not parse, validate or persist
 * glucose and cannot produce an available glucose reading.
 */
internal class DexcomSenderAuthenticator(
    private val policy: DexcomSenderTrustPolicy,
) {
    fun authenticate(evidence: DexcomBroadcastSenderEvidence): DexcomSenderAuthenticationResult {
        val approvedPolicy = policy as? DexcomSenderTrustPolicy.Approved
            ?: return rejected(DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED)

        val sentFromPackage = evidence.sentFromPackage
        val sentFromUid = evidence.sentFromUid
        if (sentFromPackage.isNullOrBlank() || sentFromUid == null || sentFromUid < 0) {
            return rejected(DexcomSenderAuthenticationFailure.IDENTITY_NOT_SHARED)
        }
        if (sentFromPackage != approvedPolicy.packageName) {
            return rejected(DexcomSenderAuthenticationFailure.PACKAGE_MISMATCH)
        }

        val resolvedPackage = evidence.resolvedPackage
            ?: return rejected(DexcomSenderAuthenticationFailure.PACKAGE_NOT_RESOLVED)
        if (resolvedPackage.packageName != sentFromPackage) {
            return rejected(DexcomSenderAuthenticationFailure.PACKAGE_MISMATCH)
        }
        if (resolvedPackage.uid != sentFromUid) {
            return rejected(DexcomSenderAuthenticationFailure.UID_MISMATCH)
        }
        val matchingVersionAnchors = approvedPolicy.releaseAnchors.filter {
            it.versionCode == resolvedPackage.versionCode
        }
        if (matchingVersionAnchors.isEmpty()) {
            return rejected(DexcomSenderAuthenticationFailure.VERSION_NOT_APPROVED)
        }
        if (matchingVersionAnchors.none {
                it.signerCertificateSha256Digests ==
                    resolvedPackage.signerCertificateSha256Digests
            }
        ) {
            return rejected(DexcomSenderAuthenticationFailure.CERTIFICATE_MISMATCH)
        }

        return DexcomSenderAuthenticationResult.Authenticated(
            packageName = resolvedPackage.packageName,
            uid = resolvedPackage.uid,
            versionCode = resolvedPackage.versionCode,
            signerCertificateSha256Digests =
                resolvedPackage.signerCertificateSha256Digests.toSet(),
        )
    }
}

internal fun rejected(
    failure: DexcomSenderAuthenticationFailure,
): DexcomSenderAuthenticationResult.Rejected =
    DexcomSenderAuthenticationResult.Rejected(
        failure = failure,
        unavailabilityReason = when (failure) {
            DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED ->
                UnavailabilityReason.POLICY_NOT_APPROVED
            DexcomSenderAuthenticationFailure.IDENTITY_NOT_SHARED,
            DexcomSenderAuthenticationFailure.PACKAGE_NOT_RESOLVED,
            DexcomSenderAuthenticationFailure.PACKAGE_LOOKUP_DENIED,
            DexcomSenderAuthenticationFailure.SIGNING_INFO_UNAVAILABLE,
            DexcomSenderAuthenticationFailure.PACKAGE_MISMATCH,
            DexcomSenderAuthenticationFailure.UID_MISMATCH,
            DexcomSenderAuthenticationFailure.VERSION_NOT_APPROVED,
            DexcomSenderAuthenticationFailure.CERTIFICATE_MISMATCH,
            -> UnavailabilityReason.AUTHENTICATION_FAILED
        },
    )

private fun canonicalSha256Digest(value: String): String {
    require(SHA256_HEX.matches(value)) {
        "dexcom_sender_policy.invalid_certificate_digest"
    }
    return value.lowercase()
}

private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
