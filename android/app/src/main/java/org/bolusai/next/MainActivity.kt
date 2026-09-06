package org.bolusai.next

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // No Dexcom producer exists yet, so absence is explicit rather than a
        // fabricated reading, cached value or zero.
        renderGlucoseStatus(
            GlucoseStatusPresenter.present(
                UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.MISSING),
            ),
        )
    }

    private fun renderGlucoseStatus(model: GlucoseStatusUiModel) {
        check(!model.allowsCalculation) { "glucose_status.unavailable_must_block" }

        val title = when (model.messageKey) {
            GlucoseStatusMessageKey.MISSING -> R.string.glucose_status_missing
            GlucoseStatusMessageKey.INVALID -> R.string.glucose_status_invalid
            GlucoseStatusMessageKey.EXPIRED -> R.string.glucose_status_expired
            GlucoseStatusMessageKey.UNKNOWN -> R.string.glucose_status_unknown
            GlucoseStatusMessageKey.INCOMPLETE -> R.string.glucose_status_incomplete
            GlucoseStatusMessageKey.CONFLICTING -> R.string.glucose_status_conflicting
            GlucoseStatusMessageKey.PERMISSION_DENIED -> R.string.glucose_status_permission_denied
            GlucoseStatusMessageKey.SOURCE_UNAVAILABLE -> R.string.glucose_status_source_unavailable
            GlucoseStatusMessageKey.AUTHENTICATION_FAILED -> R.string.glucose_status_authentication_failed
            GlucoseStatusMessageKey.CLOCK_ANOMALY -> R.string.glucose_status_clock_anomaly
            GlucoseStatusMessageKey.PARSE_FAILED -> R.string.glucose_status_parse_failed
            GlucoseStatusMessageKey.PERSISTENCE_FAILED -> R.string.glucose_status_persistence_failed
            GlucoseStatusMessageKey.POLICY_NOT_APPROVED -> R.string.glucose_status_policy_not_approved
        }

        findViewById<TextView>(R.id.glucose_status_title).setText(title)
        findViewById<TextView>(R.id.glucose_status_code).text =
            getString(R.string.glucose_status_code, model.stableCode)
    }
}
