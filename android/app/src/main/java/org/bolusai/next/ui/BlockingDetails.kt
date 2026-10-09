package org.bolusai.next.ui

import org.bolusai.engine.InputUnavailability
import org.bolusai.engine.InputUnavailabilityReport
import org.bolusai.next.application.UnavailableOverview
import org.bolusai.next.navigation.Destination

/**
 * «Detalles del bloqueo» of Bolo and Diagnóstico (ADR 0017). The only place that combines the static v1 causes of
 * [UnavailableOverview] with the contract v2 report of the profile. It reads nothing, decides nothing and allows
 * nothing: calculation and treatment stay blocked whatever it returns.
 */
internal object BlockingDetails {
    /**
     * Destinations that show this block (B10). Only they, besides Ajustes → Cálculo, may start a profile read
     * (ADR 0017, section 14). `verify.ps1` fixes this exact list.
     */
    val destinations: Set<Destination> =
        setOf(Destination.BOLUS, Destination.MANUAL, Destination.OFFLINE_BOLUS, Destination.DIAGNOSTICS)

    /**
     * One v2 report (B3): glucose, IOB and meal lifted from v1 without detail, plus the causes of the profile report.
     * The static v1 profile cause of the overview is left out: every valid profile report already contains
     * `input.profile.policy_not_approved` with its detail. Lines follow the report order, which is by code and
     * technical, never a priority.
     *
     * When the translator refused the profile state with its known identifier, there is no profile report: the lifted
     * lines are followed by the identifier, and no profile cause is fabricated (B5). Any failure to build the report
     * propagates unchanged.
     */
    fun lines(overview: UnavailableOverview, profile: ProfileUnavailabilityBlock): List<String> {
        val others = listOf(overview.glucose, overview.iob, overview.meal).map(InputUnavailability::from)
        return when (profile) {
            is ProfileUnavailabilityBlock.Report -> InputUnavailabilityReport(others + profile.report.entries)
                .entries.map { ProfileUnavailabilityBlock.line(it) }
            is ProfileUnavailabilityBlock.Rejected ->
                InputUnavailabilityReport(others).entries.map { ProfileUnavailabilityBlock.line(it) } + profile.lines
        }
    }
}
