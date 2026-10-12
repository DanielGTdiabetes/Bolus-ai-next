package org.bolusai.next.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModel
import org.bolusai.engine.candidate.CandidateResult
import org.bolusai.engine.candidate.CandidateStep
import org.bolusai.engine.candidate.StepOutcome
import org.bolusai.engine.candidate.Warnings
import org.bolusai.next.R
import org.bolusai.next.application.CarbsChoice
import org.bolusai.next.application.GlucoseChoice
import org.bolusai.next.application.TestCalculationForm
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * State of the test calculation in Bolo (ADR 0024). The result is kept only in memory: it is never recorded, confirmed
 * or synchronised. Any change of the form discards it, so a result never outlives the inputs it was computed from.
 */
internal class TestCalculationModel : ViewModel() {
    var form = TestCalculationForm()
        private set
    var result: CandidateResult? = null
        private set
    var calculatedAtEpochMs: Long? = null
        private set
    var invalidated = false
        private set

    fun update(next: TestCalculationForm) {
        if (next == form) return
        form = next
        if (result != null) invalidated = true
        result = null
        calculatedAtEpochMs = null
    }

    fun store(result: CandidateResult, atEpochMs: Long) {
        this.result = result
        calculatedAtEpochMs = atEpochMs
        invalidated = false
    }
}

/** Pure text of a result, testable without views. Labels are Spanish; codes stay visible for traceability. */
internal object TestCalculationText {
    private val steps = mapOf(
        CandidateStep.MEAL to R.string.test_calc_step_meal,
        CandidateStep.CORRECTION to R.string.test_calc_step_correction,
        CandidateStep.CORRECTION_LIMIT to R.string.test_calc_step_correction_limit,
        CandidateStep.IOB to R.string.test_calc_step_iob,
        CandidateStep.ROUNDING to R.string.test_calc_step_rounding,
        CandidateStep.MAX_BOLUS to R.string.test_calc_step_max_bolus,
        CandidateStep.HYPO_STOP to R.string.test_calc_step_hypo_stop,
    )

    private val warnings = mapOf(
        Warnings.TEST_ONLY to "Resultado de prueba: no utilizable para tratamiento",
        Warnings.RULES_NOT_APPROVED to "Reglas candidatas con validación pendiente",
        Warnings.FRESHNESS_NOT_APPROVED to "Vigencia de la glucosa sin política aprobada",
        Warnings.MEASUREMENT_TIME_UNVERIFIED to "Hora de medida de la glucosa no verificada",
        Warnings.DEXCOM_NOT_ATTRIBUTED to "Glucosa recibida del conjunto aprobado, no atribuida a Dexcom",
        Warnings.MANUAL_IOB to "IOB introducido a mano, sin verificar",
        Warnings.TEST_PARAMETERS to "Límites introducidos para esta prueba, no guardados en el perfil",
        Warnings.MAX_BOLUS_LIMITED to "Total limitado por el bolo máximo de prueba",
        Warnings.BELOW_HYPO_STOP to "Glucosa por debajo de la parada de Legacy (70 mg/dL)",
    )

    fun stepLabel(step: CandidateStep): Int = steps.getValue(step)

    fun warning(code: String): String = warnings[code]?.let { "$it ($code)" } ?: code

    /** Display only: the engine value with at most three decimals, half-even, Spanish comma. Not a clinical rounding. */
    fun units(value: Double): String =
        BigDecimal(value).setScale(3, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString().replace('.', ',')

    fun outcome(outcome: StepOutcome): String = when (outcome) {
        is StepOutcome.Computed -> units(outcome.units)
        is StepOutcome.Blocked -> outcome.causes.joinToString(", ")
    }
}

/** Form and result of the test calculation inside Bolo. Builds views only; the activity runs the engine. */
internal class TestCalculationScreen(
    private val context: Context,
    private val content: LinearLayout,
    private val model: TestCalculationModel,
    private val dexcomShowsValue: Boolean,
    private val mealAvailable: Boolean,
    private val calculate: () -> Unit,
    private val changed: () -> Unit,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun label(parent: LinearLayout, text: String, tag: String? = null, size: Float = 15f, bold: Boolean = false,
                      ink: Int = R.color.primary_text) = TextView(context).apply {
        this.text = text
        this.tag = tag
        textSize = size
        setTextColor(context.getColor(ink))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(true)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        parent.addView(this)
    }

    private fun choice(label: Int, tag: String, selected: Boolean, enabled: Boolean, run: () -> Unit) = Button(context).apply {
        setText(label)
        this.tag = tag
        isAllCaps = false
        isSelected = selected
        isEnabled = enabled
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setOnClickListener { run() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun field(hint: Int, tag: String, value: String, result: LinearLayout, update: (String) -> TestCalculationForm) {
        label(content, context.getString(hint), size = 13f, ink = R.color.secondary_text)
        EditText(context).apply {
            this.tag = tag
            setText(value)
            contentDescription = context.getString(hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val hadResult = model.result != null
                    model.update(update(s?.toString().orEmpty()))
                    if (hadResult) {
                        result.removeAllViews()
                        label(result, context.getString(R.string.test_calc_invalidated), "test_calc:invalidated")
                    }
                }
            })
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun render() {
        label(content, context.getString(R.string.test_calc_title), size = 20f, bold = true)
        label(content, context.getString(R.string.test_calc_banner), "test_calc:banner", bold = true, ink = R.color.error_text)
        val form = model.form
        val result = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        label(content, context.getString(R.string.test_calc_carbs_source), bold = true)
        choice(R.string.test_calc_carbs_manual, "test_calc:carbs:manual", form.carbsChoice == CarbsChoice.MANUAL, true) {
            model.update(model.form.copy(carbsChoice = CarbsChoice.MANUAL)); changed()
        }
        choice(R.string.test_calc_carbs_meal, "test_calc:carbs:meal", form.carbsChoice == CarbsChoice.MEAL_SELECTION,
            mealAvailable || form.carbsChoice == CarbsChoice.MEAL_SELECTION) {
            model.update(model.form.copy(carbsChoice = CarbsChoice.MEAL_SELECTION)); changed()
        }
        if (form.carbsChoice == CarbsChoice.MANUAL) {
            field(R.string.test_calc_carbs, "test_calc:carbs", form.carbs, result) { model.form.copy(carbs = it) }
        }

        label(content, context.getString(R.string.test_calc_glucose_source), bold = true)
        choice(R.string.test_calc_glucose_manual, "test_calc:glucose:manual", form.glucoseChoice == GlucoseChoice.MANUAL, true) {
            model.update(model.form.copy(glucoseChoice = GlucoseChoice.MANUAL)); changed()
        }
        choice(R.string.test_calc_glucose_dexcom, "test_calc:glucose:dexcom", form.glucoseChoice == GlucoseChoice.DEXCOM,
            dexcomShowsValue || form.glucoseChoice == GlucoseChoice.DEXCOM) {
            model.update(model.form.copy(glucoseChoice = GlucoseChoice.DEXCOM)); changed()
        }
        if (form.glucoseChoice == GlucoseChoice.MANUAL) {
            field(R.string.test_calc_glucose, "test_calc:glucose", form.glucoseMgDl, result) { model.form.copy(glucoseMgDl = it) }
        }
        field(R.string.test_calc_iob, "test_calc:iob", form.iobUnits, result) { model.form.copy(iobUnits = it) }
        label(content, context.getString(R.string.test_calc_limits), bold = true)
        field(R.string.test_calc_round_step, "test_calc:round_step", form.roundStep, result) { model.form.copy(roundStep = it) }
        field(R.string.test_calc_max_bolus, "test_calc:max_bolus", form.maxBolus, result) { model.form.copy(maxBolus = it) }
        field(R.string.test_calc_max_correction, "test_calc:max_correction", form.maxCorrection, result) {
            model.form.copy(maxCorrection = it)
        }
        Button(context).apply {
            setText(R.string.test_calc_run)
            tag = "test_calc:run"
            isAllCaps = false
            setOnClickListener { calculate() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(result, LinearLayout.LayoutParams(-1, -2))
        if (model.invalidated) label(result, context.getString(R.string.test_calc_invalidated), "test_calc:invalidated")
        model.result?.let { renderResult(result, it) }
    }

    private fun renderResult(parent: LinearLayout, result: CandidateResult) {
        label(parent, context.getString(R.string.test_calc_banner), bold = true, ink = R.color.error_text)
        label(parent, when (val total = result.total) {
            is StepOutcome.Computed -> context.getString(R.string.test_calc_total, TestCalculationText.units(total.units))
            is StepOutcome.Blocked -> context.getString(R.string.test_calc_total_blocked)
        }, "test_calc:total", 20f, true)
        result.steps.forEach { step ->
            val name = context.getString(TestCalculationText.stepLabel(step.step))
            label(parent, when (val outcome = step.outcome) {
                is StepOutcome.Computed -> context.getString(R.string.test_calc_step_computed, name, TestCalculationText.outcome(outcome))
                is StepOutcome.Blocked -> context.getString(R.string.test_calc_step_blocked, name, TestCalculationText.outcome(outcome))
            }, "test_calc:step:${step.step.code}", bold = true)
            (step.outcome as? StepOutcome.Computed)?.facts?.forEach {
                label(parent, context.getString(R.string.test_calc_fact, it.key, TestCalculationText.units(it.value)),
                    size = 13f, ink = R.color.secondary_text)
            }
        }
        label(parent, context.getString(R.string.test_calc_warnings), bold = true)
        result.warnings.forEach { label(parent, "• " + TestCalculationText.warning(it), "test_calc:warning:$it", 13f) }
        label(parent, context.getString(R.string.test_calc_not_applied, result.notApplied.joinToString(", ")), size = 13f,
            ink = R.color.secondary_text)
        label(parent, context.getString(R.string.test_calc_identity, result.engineVersion, result.ruleSetId,
            result.legacySha.take(12), result.ruleValidation), size = 12f, ink = R.color.secondary_text)
        label(parent, context.getString(R.string.test_calc_fingerprint, result.inputFingerprintSha256), "test_calc:fingerprint",
            12f, ink = R.color.secondary_text)
        model.calculatedAtEpochMs?.let {
            label(parent, context.getString(R.string.test_calc_calculated_at,
                DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))),
                size = 12f, ink = R.color.secondary_text)
        }
    }
}
