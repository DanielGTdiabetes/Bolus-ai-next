package org.bolusai.next.application

import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput
import org.bolusai.next.glucose.ReadLocalGlucoseStatus

/**
 * Current foundation has no approved engine rules. The profile cause is static and never read here: no clinical
 * approval of the profile exists, so `policy_not_approved` is true whatever is stored (ADR 0015, D7). The previous
 * static `missing` was not proven once a version could be saved.
 */
internal data class UnavailableOverview(
    val glucose: UnavailableInput,
    val profile: UnavailableInput,
    val iob: UnavailableInput,
    val meal: UnavailableInput,
) {
    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false
}

internal class ReadOverview(private val readGlucose: ReadLocalGlucoseStatus) {
    fun execute(): UnavailableOverview = UnavailableOverview(
        glucose = readGlucose.execute(),
        profile = UnavailableInput(InputKind.PROFILE, UnavailabilityReason.POLICY_NOT_APPROVED),
        iob = UnavailableInput(InputKind.IOB, UnavailabilityReason.UNKNOWN),
        meal = UnavailableInput(InputKind.MEAL, UnavailabilityReason.MISSING),
    )
}
