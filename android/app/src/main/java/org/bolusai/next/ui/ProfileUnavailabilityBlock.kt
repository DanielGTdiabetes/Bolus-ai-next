package org.bolusai.next.ui

import org.bolusai.engine.InputUnavailabilityReport
import org.bolusai.profile.ProfileGateState
import org.bolusai.profileunavailability.ProfileUnavailability
import org.bolusai.profileunavailability.ProfileUnavailabilityErrors

/**
 * Technical block of the profile screen (ADR 0016, option A). Derived only from the gate state the screen already
 * shows; it never reads storage, never decides anything and never allows calculation or treatment. Its lines are
 * contract codes, not user messages: the texts of E1 to E11 (ADR 0014) remain the visible explanation.
 */
internal sealed interface ProfileUnavailabilityBlock {
    /** One `code` per line followed by its details in brackets, in report order (A5). */
    val lines: List<String>

    data class Report(val report: InputUnavailabilityReport) : ProfileUnavailabilityBlock {
        override val lines: List<String>
            get() = report.entries.map { cause ->
                if (cause.details.isEmpty()) cause.code else "${cause.code} [${cause.details.joinToString(", ")}]"
            }
    }

    /**
     * The translator refused the state with its known stable identifier (ADR 0015, D11). No report is fabricated and
     * the received state, which still blocks calculation and treatment, is left untouched (ADR 0016, A4).
     */
    data class Rejected(val identifier: String) : ProfileUnavailabilityBlock {
        override val lines: List<String> get() = listOf(identifier)
    }

    companion object {
        /**
         * A gate state not proven yet (`null`) is the pending read the screen already shows as E1. Only the known
         * rejection of the translator is caught; any other exception propagates unchanged.
         */
        fun of(
            gate: ProfileGateState?,
            translate: (ProfileGateState) -> InputUnavailabilityReport = ProfileUnavailability::report,
        ): ProfileUnavailabilityBlock = try {
            Report(translate(gate ?: ProfileGateState.ReadPending))
        } catch (rejection: IllegalArgumentException) {
            if (rejection.message != ProfileUnavailabilityErrors.UNREADABLE_REASON_NOT_SUPPORTED) throw rejection
            Rejected(ProfileUnavailabilityErrors.UNREADABLE_REASON_NOT_SUPPORTED)
        }
    }
}
