package org.bolusai.next

import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput

internal enum class GlucoseStatusMessageKey {
    MISSING,
    PERMISSION_DENIED,
    SOURCE_UNAVAILABLE,
    OTHER_UNAVAILABLE,
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
            UnavailabilityReason.PERMISSION_DENIED -> GlucoseStatusMessageKey.PERMISSION_DENIED
            UnavailabilityReason.SOURCE_UNAVAILABLE -> GlucoseStatusMessageKey.SOURCE_UNAVAILABLE
            else -> GlucoseStatusMessageKey.OTHER_UNAVAILABLE
        }

        return GlucoseStatusUiModel(
            messageKey = messageKey,
            stableCode = unavailableInput.code,
            allowsCalculation = false,
        )
    }
}
