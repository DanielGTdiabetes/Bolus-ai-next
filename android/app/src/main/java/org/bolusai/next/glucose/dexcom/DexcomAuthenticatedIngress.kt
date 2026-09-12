package org.bolusai.next.glucose.dexcom

/** Technical dispatch only: a handled value is not a validated glucose reading. */
internal sealed interface DexcomIngressResult<out T> {
    data class Rejected(val authentication: DexcomSenderAuthenticationResult.Rejected) :
        DexcomIngressResult<Nothing>

    data class Handled<T>(val value: T) : DexcomIngressResult<T>
}

/**
 * Invoke synchronously inside onReceive. Keep every payload access inside
 * afterAuthentication; constructing the callback must not read Intent extras.
 * Each invocation authenticates afresh. This boundary has no storage or parser.
 */
internal class DexcomAuthenticatedIngress(policy: DexcomSenderTrustPolicy) {
    private val authentication = DexcomSenderEvidenceAuthentication(policy)

    fun <T> receive(
        source: DexcomSenderEvidenceSource,
        afterAuthentication: (DexcomSenderAuthenticationResult.Authenticated) -> T,
    ): DexcomIngressResult<T> = when (val sender = authentication.authenticate(source)) {
        is DexcomSenderAuthenticationResult.Rejected -> DexcomIngressResult.Rejected(sender)
        is DexcomSenderAuthenticationResult.Authenticated ->
            DexcomIngressResult.Handled(afterAuthentication(sender))
    }
}
