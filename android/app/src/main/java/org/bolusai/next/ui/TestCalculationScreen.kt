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
import org.bolusai.engine.candidate.CandidateRequest
import org.bolusai.engine.candidate.CandidateResult
import org.bolusai.engine.candidate.CandidateStep
import org.bolusai.engine.candidate.ExerciseIntensity
import org.bolusai.engine.candidate.Input
import org.bolusai.engine.candidate.ProfileSnapshot
import org.bolusai.engine.candidate.Quantity
import org.bolusai.engine.candidate.StepOutcome
import org.bolusai.engine.candidate.Warnings
import org.bolusai.engine.candidate.WarsawStrategy
import org.bolusai.engine.iob.IobOutcome
import org.bolusai.engine.iob.IobResult
import org.bolusai.next.application.IobChoice
import org.bolusai.next.R
import org.bolusai.next.application.CarbsChoice
import org.bolusai.next.application.GlucoseChoice
import org.bolusai.next.application.TestCalculationDependencies
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
    /** The exact request [result] was computed from, kept to build the audit record of a noted dose (ADR 0028). */
    var request: CandidateRequest? = null
        private set
    /** The candidate IOB the result used, when the form takes the IOB from the dose history (ADR 0028). */
    var iob: IobResult? = null
        private set
    var invalidated = false
        private set
    private var dependencies: TestCalculationDependencies? = null

    fun update(next: TestCalculationForm) {
        if (next == form) return
        form = next
        if (result != null) invalidated = true
        result = null
        request = null
        iob = null
        calculatedAtEpochMs = null
    }

    /** Drops the result after it was noted as an administered dose, so it cannot be noted twice. */
    fun discard() {
        result = null
        request = null
        iob = null
        calculatedAtEpochMs = null
        dependencies = null
    }

    fun store(result: CandidateResult, atEpochMs: Long, dependencies: TestCalculationDependencies, iob: IobResult? = null,
              request: CandidateRequest? = null) {
        this.result = result
        this.request = request
        this.iob = iob
        this.dependencies = dependencies
        calculatedAtEpochMs = atEpochMs
        invalidated = false
    }

    /** Discards the result when a reading, meal or profile it used is no longer the current one. */
    fun invalidateIfChanged(current: TestCalculationDependencies) {
        if (result == null || current == dependencies) return
        result = null
        request = null
        iob = null
        calculatedAtEpochMs = null
        dependencies = null
        invalidated = true
    }
}

/** Pure text of a result, testable without views. Labels are Spanish; codes stay visible for traceability. */
internal object TestCalculationText {
    private val steps = mapOf(
        CandidateStep.MEAL to R.string.test_calc_step_meal,
        CandidateStep.EXTENDED to R.string.test_calc_step_extended,
        CandidateStep.CORRECTION to R.string.test_calc_step_correction,
        CandidateStep.CORRECTION_LIMIT to R.string.test_calc_step_correction_limit,
        CandidateStep.IOB to R.string.test_calc_step_iob,
        CandidateStep.EXERCISE to R.string.test_calc_step_exercise,
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
        Warnings.CALCULATED_IOB to "IOB calculado del historial local con reglas candidatas, sin verificar",
        Warnings.MAX_BOLUS_LIMITED to "Total limitado por el bolo máximo del perfil",
        Warnings.BELOW_HYPO_STOP to "Glucosa por debajo de la parada de Legacy (70 mg/dL)",
    )

    fun stepLabel(step: CandidateStep): Int = steps.getValue(step)

    fun warning(code: String): String = warnings[code]?.let { "$it ($code)" } ?: code

    /** Display only: the engine value with at most three decimals, half-even, Spanish comma. Not a clinical rounding. */
    fun units(value: Double): String =
        BigDecimal(value).setScale(3, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString().replace('.', ',')

    /**
     * Read-only lines with the limits of the profile version used, so the user sees where they come from. They are
     * edited only in the profile; an unconfirmed version is shown but the engine blocks the steps that need it.
     */
    fun limits(profile: Input<ProfileSnapshot>): String = when (profile) {
        is Input.Unavailable -> "Límites del perfil no disponibles (${profile.cause})"
        is Input.Provided -> {
            val snapshot = profile.value
            fun line(name: String, value: Input<Quantity>) = "$name: " + when (value) {
                is Input.Provided -> "${value.value.text.replace('.', ',')} U"
                is Input.Unavailable -> "desconocido (${value.cause})"
            }
            fun rule(name: String, value: Input<Quantity>, unit: String) = "$name: " + when (value) {
                is Input.Provided -> "${value.value.text.replace('.', ',')} $unit" + if (value.value.value == 0.0 && unit == "×") " (regla desactivada)" else ""
                is Input.Unavailable -> "desconocido (${value.cause})"
            }
            listOf(
                "Perfil v${snapshot.version}" + if (snapshot.dataConfirmed) " confirmado" else " sin confirmar (profile.unconfirmed)",
                line("Paso de redondeo", snapshot.roundStep),
                line("Bolo máximo", snapshot.maxBolus),
                line("Corrección máxima", snapshot.maxCorrection),
                rule("Fibra: factor de descuento", snapshot.nutritionRules.fiberFactor, "×"),
                rule("Fibra: umbral", snapshot.nutritionRules.fiberThresholdG, "g"),
                rule("Warsaw: factor simple", snapshot.nutritionRules.warsawFactorSimple, "×"),
                rule("Warsaw: factor dual", snapshot.nutritionRules.warsawFactorDual, "×"),
                rule("Warsaw: umbral", snapshot.nutritionRules.warsawTriggerKcal, "kcal"),
            ).joinToString("\n")
        }
    }

    /**
     * Breakdown of the candidate IOB the result used: total, every dose with insulin left and its provenance, or the
     * causes that block it. A blocked IOB is never shown as zero.
     */
    fun iob(result: IobResult): List<String> = when (val outcome = result.outcome) {
        is IobOutcome.Blocked -> listOf("IOB del historial: bloqueado (${outcome.causes.joinToString(", ")})")
        is IobOutcome.Computed -> listOf("IOB del historial: ${units(outcome.units)} U · ${outcome.contributions.size} dosis " +
            "en el registro · acción efectiva ${units(outcome.effectiveActionMinutes)} min") +
            outcome.contributions.filter { it.remainingUnits > 0.0 }.map {
                "   ${units(it.units)} U hace ${units(it.elapsedMinutes)} min → quedan ${units(it.remainingUnits)} U"
            }
    } + ("IOB: reglas ${result.ruleSetId} · Legacy ${result.legacySha.take(12)} · validación ${result.ruleValidation} · " +
        "huella ${result.inputFingerprintSha256}")

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
    /** The profile version the calculation will use, as the engine receives it; only its limits are shown. */
    private val profile: Input<ProfileSnapshot>,
    private val mealAvailable: Boolean,
    private val now: () -> Long,
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

    private fun choice(label: String, tag: String, selected: Boolean, run: () -> Unit) = Button(context).apply {
        text = label
        this.tag = tag
        isAllCaps = false
        isSelected = selected
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setOnClickListener { run() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
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
            field(R.string.test_calc_fiber, "test_calc:fiber", form.fiber, result) { model.form.copy(fiber = it) }
            field(R.string.test_calc_fat, "test_calc:fat", form.fat, result) { model.form.copy(fat = it) }
            field(R.string.test_calc_protein, "test_calc:protein", form.protein, result) { model.form.copy(protein = it) }
        }
        label(content, "Estrategia Warsaw", bold = true)
        choice("Automática: factor dual a partir del umbral (como bolus_ai)", "test_calc:warsaw:auto",
            form.warsawStrategy == WarsawStrategy.AUTO) {
            model.update(model.form.copy(warsawStrategy = WarsawStrategy.AUTO)); changed()
        }
        choice("Normal: siempre factor simple", "test_calc:warsaw:normal", form.warsawStrategy == WarsawStrategy.NORMAL) {
            model.update(model.form.copy(warsawStrategy = WarsawStrategy.NORMAL)); changed()
        }

        label(content, "Ejercicio", bold = true)
        choice("Sin ejercicio previsto", "test_calc:exercise:none", !form.exercisePlanned) {
            model.update(model.form.copy(exercisePlanned = false)); changed()
        }
        choice("Ejercicio previsto: reduce el bolo", "test_calc:exercise:planned", form.exercisePlanned) {
            model.update(model.form.copy(exercisePlanned = true)); changed()
        }
        if (form.exercisePlanned) {
            field(R.string.test_calc_exercise_minutes, "test_calc:exercise:minutes", form.exerciseMinutes, result) {
                model.form.copy(exerciseMinutes = it)
            }
            listOf(ExerciseIntensity.LOW to "Intensidad baja", ExerciseIntensity.MODERATE to "Intensidad moderada",
                ExerciseIntensity.HIGH to "Intensidad alta").forEach { (intensity, name) ->
                choice(name, "test_calc:exercise:${intensity.code}", form.exerciseIntensity == intensity) {
                    model.update(model.form.copy(exerciseIntensity = intensity)); changed()
                }
            }
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
            field(R.string.test_calc_glucose, "test_calc:glucose", form.glucoseMgDl, result) {
                model.form.copy(glucoseMgDl = it, glucoseEnteredAtEpochMs = now())
            }
        }
        label(content, "IOB", bold = true)
        choice("Introducir a mano", "test_calc:iob:manual", form.iobChoice == IobChoice.MANUAL) {
            model.update(model.form.copy(iobChoice = IobChoice.MANUAL)); changed()
        }
        choice("Calcular del historial de dosis local", "test_calc:iob:history", form.iobChoice == IobChoice.DOSE_HISTORY) {
            model.update(model.form.copy(iobChoice = IobChoice.DOSE_HISTORY)); changed()
        }
        if (form.iobChoice == IobChoice.MANUAL) {
            field(R.string.test_calc_iob, "test_calc:iob", form.iobUnits, result) { model.form.copy(iobUnits = it) }
        } else {
            label(content, "Al calcular se usa el IOB candidato del historial de dosis de este móvil. Si el registro " +
                "no cubre toda la acción de la insulina, el IOB se bloquea: nunca se toma como 0.", "test_calc:iob:history_note", 13f)
        }
        label(content, context.getString(R.string.test_calc_limits), bold = true)
        label(content, TestCalculationText.limits(profile), "test_calc:limits", 13f)
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
        model.iob?.let { iob -> TestCalculationText.iob(iob).forEachIndexed { index, line ->
            label(parent, line, if (index == 0) "test_calc:iob_detail" else null, 13f, ink = R.color.secondary_text)
        } }
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
