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
            GlucoseStatusMessageKey.PERMISSION_DENIED -> R.string.glucose_status_permission_denied
            GlucoseStatusMessageKey.SOURCE_UNAVAILABLE -> R.string.glucose_status_source_unavailable
            GlucoseStatusMessageKey.OTHER_UNAVAILABLE -> R.string.glucose_status_other_unavailable
        }

        findViewById<TextView>(R.id.glucose_status_title).setText(title)
        findViewById<TextView>(R.id.glucose_status_code).text =
            getString(R.string.glucose_status_code, model.stableCode)
    }
}
