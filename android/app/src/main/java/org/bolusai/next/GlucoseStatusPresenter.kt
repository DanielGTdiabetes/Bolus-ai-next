package org.bolusai.next

import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput

internal enum class GlucoseStatusMessageKey {
    MISSING,
    INVALID,
    EXPIRED,
    UNKNOWN,
    INCOMPLETE,
    CONFLICTING,
    PERMISSION_DENIED,
    SOURCE_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    CLOCK_ANOMALY,
    PARSE_FAILED,
    PERSISTENCE_FAILED,
    POLICY_NOT_APPROVED,
}

internal data class GlucoseStatusUiModel(
    val messageKey: GlucoseStatusMessageKey,
    val stableCode: String,
    val allowsCalculation: Boolean,
)

/**
 * Maps an already-established domain cause to display state. It does not inspect
 * providers, infer permissions or decide whether a reading is clinically valid.
 */
internal object GlucoseStatusPresenter {
    fun present(unavailableInput: UnavailableInput): GlucoseStatusUiModel {
        require(unavailableInput.input == InputKind.GLUCOSE) {
            "glucose_status.wrong_input"
        }

        val messageKey = when (unavailableInput.reason) {
            UnavailabilityReason.MISSING -> GlucoseStatusMessageKey.MISSING
            UnavailabilityReason.INVALID -> GlucoseStatusMessageKey.INVALID
            UnavailabilityReason.EXPIRED -> GlucoseStatusMessageKey.EXPIRED
            UnavailabilityReason.UNKNOWN -> GlucoseStatusMessageKey.UNKNOWN
            UnavailabilityReason.INCOMPLETE -> GlucoseStatusMessageKey.INCOMPLETE
            UnavailabilityReason.CONFLICTING -> GlucoseStatusMessageKey.CONFLICTING
            UnavailabilityReason.PERMISSION_DENIED -> GlucoseStatusMessageKey.PERMISSION_DENIED
            UnavailabilityReason.SOURCE_UNAVAILABLE -> GlucoseStatusMessageKey.SOURCE_UNAVAILABLE
            UnavailabilityReason.AUTHENTICATION_FAILED -> GlucoseStatusMessageKey.AUTHENTICATION_FAILED
            UnavailabilityReason.CLOCK_ANOMALY -> GlucoseStatusMessageKey.CLOCK_ANOMALY
            UnavailabilityReason.PARSE_FAILED -> GlucoseStatusMessageKey.PARSE_FAILED
            UnavailabilityReason.PERSISTENCE_FAILED -> GlucoseStatusMessageKey.PERSISTENCE_FAILED
            UnavailabilityReason.POLICY_NOT_APPROVED -> GlucoseStatusMessageKey.POLICY_NOT_APPROVED
        }

        return GlucoseStatusUiModel(
            messageKey = messageKey,
            stableCode = unavailableInput.code,
            allowsCalculation = false,
        )
    }
}
