package org.bolusai.next.application

import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput
import org.bolusai.next.glucose.ReadLocalGlucoseStatus

/** Current foundation has no clinical data store or approved engine rules. */
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
        profile = UnavailableInput(InputKind.PROFILE, UnavailabilityReason.MISSING),
        iob = UnavailableInput(InputKind.IOB, UnavailabilityReason.UNKNOWN),
        meal = UnavailableInput(InputKind.MEAL, UnavailabilityReason.MISSING),
    )
}
