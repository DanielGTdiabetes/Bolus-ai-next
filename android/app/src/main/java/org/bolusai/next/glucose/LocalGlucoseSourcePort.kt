package org.bolusai.next.glucose

import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput

/**
 * Android boundary for a future local glucose producer.
 *
 * There is deliberately no available-reading variant until the Dexcom contract,
 * units, timestamps, identity and validation policies are approved. Adding that
 * variant will make the exhaustive use-case mapping fail to compile until it is
 * reviewed explicitly.
 */
internal sealed interface LocalGlucoseSourceResult {
    data class Unavailable(
        val reason: UnavailabilityReason,
    ) : LocalGlucoseSourceResult
}

internal fun interface LocalGlucoseSourcePort {
    fun readLatest(): LocalGlucoseSourceResult
}

/** Default source while no authenticated and authorized Dexcom adapter exists. */
internal object PendingDexcomSource : LocalGlucoseSourcePort {
    override fun readLatest(): LocalGlucoseSourceResult =
        LocalGlucoseSourceResult.Unavailable(UnavailabilityReason.POLICY_NOT_APPROVED)
}

/**
 * Converts a source-owned result into the shared value-free blocking contract.
 * It does not catch, relabel or collapse failures from a future adapter.
 */
internal class ReadLocalGlucoseStatus(
    private val source: LocalGlucoseSourcePort,
) {
    fun execute(): UnavailableInput =
        when (val result = source.readLatest()) {
            is LocalGlucoseSourceResult.Unavailable ->
                UnavailableInput(InputKind.GLUCOSE, result.reason)
        }
}
