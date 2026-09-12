package org.bolusai.next.glucose.dexcom

/** Only system identity metadata; this port deliberately has no payload API. */
internal interface DexcomSenderEvidenceSource {
    fun attributedIdentity(): AttributedSenderIdentity
    fun resolvePackage(packageName: String): SenderPackageResolution
}

/** Null means Android did not expose that part of the sender identity. */
internal data class AttributedSenderIdentity(val packageName: String?, val uid: Int?)

internal sealed interface SenderPackageResolution {
    data class Resolved(val identity: ResolvedAndroidPackageIdentity) : SenderPackageResolution
    data object NotFound : SenderPackageResolution
    data object AccessDenied : SenderPackageResolution
    data object SigningInfoUnavailable : SenderPackageResolution
}

/** Checks policy and attributed identity before querying any package metadata. */
internal class DexcomSenderEvidenceAuthentication(private val policy: DexcomSenderTrustPolicy) {
    fun authenticate(source: DexcomSenderEvidenceSource): DexcomSenderAuthenticationResult {
        if (policy is DexcomSenderTrustPolicy.Pending) {
            return rejected(DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED)
        }
        val identity = source.attributedIdentity()
        val evidence = DexcomBroadcastSenderEvidence(identity.packageName, identity.uid, null)
        val authenticator = DexcomSenderAuthenticator(policy)
        val preliminary = authenticator.authenticate(evidence)
        check(preliminary is DexcomSenderAuthenticationResult.Rejected)
        if (preliminary.failure != DexcomSenderAuthenticationFailure.PACKAGE_NOT_RESOLVED) {
            return preliminary
        }
        return when (val resolution = source.resolvePackage(checkNotNull(identity.packageName))) {
            is SenderPackageResolution.Resolved -> authenticator.authenticate(
                evidence.copy(resolvedPackage = resolution.identity),
            )
            SenderPackageResolution.NotFound -> rejected(
                DexcomSenderAuthenticationFailure.PACKAGE_NOT_RESOLVED,
            )
            SenderPackageResolution.AccessDenied -> rejected(
                DexcomSenderAuthenticationFailure.PACKAGE_LOOKUP_DENIED,
            )
            SenderPackageResolution.SigningInfoUnavailable -> rejected(
                DexcomSenderAuthenticationFailure.SIGNING_INFO_UNAVAILABLE,
            )
        }
    }
}
