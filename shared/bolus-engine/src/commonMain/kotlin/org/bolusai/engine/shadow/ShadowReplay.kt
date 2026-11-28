package org.bolusai.engine.shadow

import org.bolusai.engine.candidate.CandidateEngine
import org.bolusai.engine.candidate.CandidateRequest
import org.bolusai.engine.candidate.CandidateResult
import org.bolusai.engine.candidate.CandidateStep
import org.bolusai.engine.candidate.CarbsOrigin
import org.bolusai.engine.candidate.CarbsValue
import org.bolusai.engine.candidate.GlucoseOrigin
import org.bolusai.engine.candidate.GlucoseUnitCode
import org.bolusai.engine.candidate.GlucoseValue
import org.bolusai.engine.candidate.Input
import org.bolusai.engine.candidate.IobValue
import org.bolusai.engine.candidate.MealNutrition
import org.bolusai.engine.candidate.NutritionRules
import org.bolusai.engine.candidate.ProfileSnapshot
import org.bolusai.engine.candidate.Quantity
import org.bolusai.engine.candidate.SegmentValue
import org.bolusai.engine.candidate.StepOutcome
import org.bolusai.engine.candidate.WarsawStrategy
import kotlin.math.abs
import kotlin.math.max

/**
 * Shadow replay (ADR 0030, plan phase 9): one calculation Legacy stored in `treatments.calculation_trace`, replayed
 * through the single candidate engine and compared component by component. Nothing here reads a network or a
 * database, records a treatment or changes Legacy: the caller passes minimised text records and gets a comparison.
 *
 * A comparison is a match only when the inputs are known to be equivalent. Anything Legacy applied that Next does
 * not (Techne, exercise, IOB ceiling, stale or absent glucose) or anything the trace does not carry makes
 * the record not comparable; an unexplained difference is a mismatch and is never counted as a match.
 */
public enum class ShadowClass {
    /** Same components and total with the inputs of the trace. */
    MATCH,

    /** Same result, but the fibre and Warsaw settings in force at the time could not be verified by their hash. */
    MATCH_SETTINGS_UNVERIFIED,

    /** Same result only with the `normal` Warsaw strategy, which the trace does not record. */
    MATCH_STRATEGY_INFERRED,

    /** Same result within what the trace keeps: two decimals of the IOB, three of the autosens-adjusted ratios. */
    MATCH_WITHIN_TRACE_PRECISION,

    /** Equivalent inputs, different result. To be explained, reproduced and covered by a test. */
    MISMATCH,

    /** The inputs cannot be shown to be equivalent. Never a match. */
    NOT_COMPARABLE,
}

/** One compared number. [legacy] is what the trace stored, [next] what the engine returned with the same inputs. */
public data class ComponentDiff(val name: String, val legacy: Double, val next: Double, val tolerance: Double) {
    val equal: Boolean get() = abs(legacy - next) <= tolerance
}

public data class ShadowComparison(
    val reference: String,
    val shadowClass: ShadowClass,
    /** Stable codes: why it is not comparable, or what had to be assumed for a qualified match. */
    val reasons: List<String>,
    val components: List<ComponentDiff>,
)

/** Fibre and Warsaw settings of Legacy as exported for the replay. A switch that is off becomes a factor of 0. */
public data class LegacyRuleSettings(
    val configHash: String?,
    val fiberDeduction: Boolean,
    val fiberFactor: String,
    val fiberThreshold: String,
    val warsawEnabled: Boolean,
    val warsawFactorSimple: String,
    val warsawFactorDual: String,
    val warsawTrigger: String,
)

/** One stored Legacy calculation as minimised text fields; an absent or null field is missing from [fields]. */
public data class LegacyCalculation(val reference: String, val fields: Map<String, String>)

public data class ShadowInput(val legacySha: String, val settings: LegacyRuleSettings, val calculations: List<LegacyCalculation>)

public object ShadowReplay {
    public const val FORMAT: String = "bolus-ai-next/shadow-legacy-calculations/v1"

    /** The trace keeps meal, correction and applied IOB with two decimals (`round(x, 2)` in Legacy). */
    public const val TRACE_COMPONENT_TOLERANCE: Double = 0.005 + 1e-9

    /** The total is stored unrounded; only binary noise is tolerated. */
    public const val TOTAL_TOLERANCE: Double = 1e-9

    /** Half of the last decimal the trace keeps of the ratio and the sensitivity adjusted by autosens. */
    public const val TRACE_RATIO_HALF_STEP: Double = 0.0005

    /** Half of the last decimal the trace keeps of the IOB. */
    public const val TRACE_IOB_HALF_STEP: Double = 0.005

    private const val NULL = "~"
    private val NO_PROFILE_FINGERPRINT = "0".repeat(64)

    /**
     * Strict parser of the minimised text written by `scripts/shadow/extract_legacy_calculations.py`: `key=value`
     * lines, a blank line between blocks, the first block with the format, the audited Legacy SHA and the settings,
     * then one block per calculation starting with `record=<reference>`. `~` marks a null value.
     */
    public fun parse(text: String): ShadowInput {
        val blocks = text.replace("\r\n", "\n").trim('\n').split("\n\n").map { block ->
            block.split('\n').map { line ->
                val at = line.indexOf('=')
                require(at > 0) { "shadow.input.malformed_line" }
                line.substring(0, at) to line.substring(at + 1)
            }
        }
        require(blocks.isNotEmpty()) { "shadow.input.empty" }
        val header = blocks.first().toMap()
        require(header["format"] == FORMAT) { "shadow.input.unknown_format" }
        fun setting(key: String) = requireNotNull(header["settings.$key"]) { "shadow.input.missing_setting.$key" }
        val settings = LegacyRuleSettings(
            configHash = setting("config_hash").takeIf { it != NULL },
            fiberDeduction = setting("fiber_deduction").toBooleanStrict(),
            fiberFactor = setting("fiber_factor"),
            fiberThreshold = setting("fiber_threshold"),
            warsawEnabled = setting("warsaw_enabled").toBooleanStrict(),
            warsawFactorSimple = setting("warsaw_factor_simple"),
            warsawFactorDual = setting("warsaw_factor_dual"),
            warsawTrigger = setting("warsaw_trigger"),
        )
        val calculations = blocks.drop(1).map { block ->
            require(block.first().first == "record") { "shadow.input.block_without_record" }
            LegacyCalculation(block.first().second, block.drop(1).filter { it.second != NULL }.toMap())
        }
        require(calculations.map { it.reference }.toSet().size == calculations.size) { "shadow.input.duplicate_record" }
        return ShadowInput(requireNotNull(header["legacy_sha"]) { "shadow.input.missing_legacy_sha" }, settings, calculations)
    }

    public fun compare(calculation: LegacyCalculation, settings: LegacyRuleSettings): ShadowComparison {
        val f = calculation.fields
        val reasons = mutableListOf<String>()
        fun notComparable() = ShadowComparison(calculation.reference, ShadowClass.NOT_COMPARABLE, reasons.distinct(), emptyList())

        fun quantity(name: String): Quantity? {
            val text = f[name] ?: run { reasons += "trace.missing.$name"; return null }
            if (!Quantity.isCanonical(text)) { reasons += "value.not_representable.$name"; return null }
            return Quantity(text)
        }
        fun number(name: String): Double? {
            val value = f[name]?.toDoubleOrNull()
            if (value == null || !value.isFinite()) reasons += "trace.missing.$name"
            return value?.takeIf { it.isFinite() }
        }

        val carbs = quantity("carbs")
        val fiber = quantity("fiber")
        val fat = quantity("fat")
        val protein = quantity("protein")
        val cr = quantity("cr")
        val isf = quantity("isf")
        val target = quantity("target")
        val roundStep = quantity("round_step")
        val maxBolus = quantity("max_bolus")
        val maxCorrection = quantity("max_correction")
        val iob = number("iob")
        val expectedTotal = number("expected.total_u")
        val expectedMeal = number("expected.meal_u")
        val expectedCorrection = number("expected.correction_u")
        val expectedIobApplied = number("expected.iob_applied_u")

        // What Legacy applied and Next does not. Each one makes the inputs non-equivalent.
        val glucose = if ("glucose" !in f) { reasons += "legacy.glucose_absent"; null } else quantity("glucose")
        when (f["glucose_stale"]) {
            "false" -> Unit
            "true" -> reasons += "legacy.glucose_stale"
            else -> reasons += "trace.missing.glucose_stale"
        }
        // Autosens only changes the ratio and the sensitivity, and the trace stores both already adjusted (with three
        // decimals), so the replay uses them as given. It is noted, not excluded.
        val notes = mutableListOf<String>()
        when (val ratio = f["autosens_ratio"]?.toDoubleOrNull()) {
            null -> reasons += "trace.missing.autosens_ratio"
            1.0 -> Unit
            else -> if (ratio.isFinite()) notes += "autosens.effective_ratios_replayed" else reasons += "trace.missing.autosens_ratio"
        }
        when (f["techne_enabled"]) {
            "false" -> Unit
            "true" -> if (!f["trend"].isNullOrBlank()) reasons += "legacy.techne_rounding_possible"
            else -> reasons += "trace.missing.techne_enabled"
        }
        when (f["exercise"]) {
            "false" -> Unit
            "true" -> reasons += "legacy.exercise_reduction_applied"
            else -> reasons += "trace.missing.exercise"
        }
        if (iob != null && iob < 0.0) reasons += "trace.negative_iob"
        if (reasons.isNotEmpty()) return notComparable()

        val rules = NutritionRules(
            fiberFactor = rule(if (settings.fiberDeduction) settings.fiberFactor else "0", "fiber_factor"),
            fiberThresholdG = rule(settings.fiberThreshold, "fiber_threshold"),
            warsawFactorSimple = rule(if (settings.warsawEnabled) settings.warsawFactorSimple else "0", "warsaw_factor_simple"),
            warsawFactorDual = rule(if (settings.warsawEnabled) settings.warsawFactorDual else "0", "warsaw_factor_dual"),
            warsawTriggerKcal = rule(settings.warsawTrigger, "warsaw_trigger"),
        )
        fun allDay(value: Quantity) = listOf(SegmentValue(0, CandidateRequest.MINUTES_PER_DAY, Input.Provided(value)))
        fun run(strategy: WarsawStrategy, iobUnits: Double): CandidateResult = CandidateEngine.calculate(CandidateRequest(
            calculationAtEpochMs = 0L,
            localMinuteOfDay = Input.Provided(0),
            // The trace carries the values Legacy resolved, not a Next profile: no version of Next is implied.
            profile = Input.Provided(ProfileSnapshot(1, NO_PROFILE_FINGERPRINT, dataConfirmed = true,
                glucoseUnit = Input.Provided(GlucoseUnitCode.MG_DL), timeZoneId = Input.Unavailable("shadow.replay.no_zone"),
                carbRatio = allDay(cr!!), sensitivity = allDay(isf!!), target = allDay(target!!),
                roundStep = Input.Provided(roundStep!!), maxBolus = Input.Provided(maxBolus!!),
                maxCorrection = Input.Provided(maxCorrection!!), nutritionRules = rules)),
            carbs = Input.Provided(CarbsValue(carbs!!, CarbsOrigin.MANUAL_ENTRY)),
            glucose = Input.Provided(GlucoseValue(glucose!!, GlucoseUnitCode.MG_DL, GlucoseOrigin.MANUAL_ENTRY, 0L,
                measurementTimeVerified = false, sourceReference = "legacy_trace")),
            iob = Input.Provided(IobValue.replayed(iobUnits, calculation.reference)),
            nutrition = MealNutrition(Input.Provided(fiber!!), Input.Provided(fat!!), Input.Provided(protein!!)),
            warsawStrategy = strategy,
        ))

        fun diffs(result: CandidateResult): List<ComponentDiff>? {
            val total = (result.total as? StepOutcome.Computed)?.units ?: return null
            return listOf(
                ComponentDiff("meal_u", expectedMeal!!, units(result, CandidateStep.MEAL), TRACE_COMPONENT_TOLERANCE),
                ComponentDiff("correction_u", expectedCorrection!!, units(result, CandidateStep.CORRECTION_LIMIT),
                    TRACE_COMPONENT_TOLERANCE),
                ComponentDiff("iob_applied_u", expectedIobApplied!!, fact(result, CandidateStep.IOB,
                    "iob_applied_to_correction_u"), TRACE_COMPONENT_TOLERANCE),
                ComponentDiff("total_u", expectedTotal!!, total, TOTAL_TOLERANCE),
            )
        }

        val base = run(WarsawStrategy.AUTO, iob!!)
        val baseDiffs = diffs(base) ?: run {
            val causes = base.steps.mapNotNull { it.outcome as? StepOutcome.Blocked }.flatMap { it.causes }
                .filterNot { it.startsWith("depends_on.") }
            reasons += causes.map { "next.blocked.$it" }.ifEmpty { listOf("next.blocked") }
            return notComparable()
        }

        // Legacy IOB ceiling: not applied by Next. It only matters when it would have reduced the dose.
        f["max_iob"]?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { ceiling ->
            val projected = iob + fact(base, CandidateStep.ROUNDING, "unrounded_u") +
                fact(base, CandidateStep.ROUNDING, "unrounded_extended_u")
            if (projected > ceiling) {
                reasons += "legacy.max_iob_ceiling_applies"
                return notComparable()
            }
        }

        val hasNutrients = fiber!!.value > 0.0 || fat!!.value > 0.0 || protein!!.value > 0.0
        val settingsVerified = !hasNutrients || (settings.configHash != null && f["config_hash"] == settings.configHash)
        fun matched(klass: ShadowClass, list: List<ComponentDiff>, vararg why: String): ShadowComparison {
            val unverified = !settingsVerified
            return ShadowComparison(calculation.reference,
                if (unverified && klass == ShadowClass.MATCH) ShadowClass.MATCH_SETTINGS_UNVERIFIED else klass,
                why.toList() + (if (unverified) listOf("settings.unverified_for_nutrients") else emptyList()) + notes, list)
        }
        if (baseDiffs.all { it.equal }) return matched(ShadowClass.MATCH, baseDiffs)

        // The trace does not record the Warsaw strategy of the request.
        if (hasNutrients) {
            diffs(run(WarsawStrategy.NORMAL, iob))?.takeIf { list -> list.all { it.equal } }?.let {
                return matched(ShadowClass.MATCH_STRATEGY_INFERRED, it, "warsaw_strategy.normal_inferred")
            }
        }
        // The trace keeps the IOB with two decimals. The total never rises with more IOB, so the Legacy total is
        // explained when it lies between the totals at both ends of the rounding interval.
        val low = (run(WarsawStrategy.AUTO, iob + TRACE_IOB_HALF_STEP).total as? StepOutcome.Computed)?.units
        val high = (run(WarsawStrategy.AUTO, max(0.0, iob - TRACE_IOB_HALF_STEP)).total as? StepOutcome.Computed)?.units
        val independentOfIob = baseDiffs.filter { it.name == "meal_u" || it.name == "correction_u" }.all { it.equal }
        val appliedWithinInterval = baseDiffs.first { it.name == "iob_applied_u" }
            .let { abs(it.legacy - it.next) <= TRACE_COMPONENT_TOLERANCE + TRACE_IOB_HALF_STEP }
        if (low != null && high != null && independentOfIob && appliedWithinInterval &&
            expectedTotal!! >= low - TOTAL_TOLERANCE && expectedTotal <= high + TOTAL_TOLERANCE
        ) {
            return matched(ShadowClass.MATCH_WITHIN_TRACE_PRECISION, baseDiffs, "iob.trace_rounded_to_two_decimals")
        }
        // With autosens the trace stores the adjusted ratio and sensitivity with three decimals. The same total with
        // components inside the error those decimals allow is explained by the trace, not by a rule.
        if (notes.isNotEmpty() && baseDiffs.first { it.name == "total_u" }.equal) {
            val mealSlack = abs(baseDiffs.first { it.name == "meal_u" }.next) * TRACE_RATIO_HALF_STEP / cr!!.value
            val correctionSlack = abs(baseDiffs.first { it.name == "correction_u" }.next) * TRACE_RATIO_HALF_STEP / isf!!.value
            val explained = baseDiffs.all {
                val slack = when (it.name) {
                    "meal_u" -> mealSlack
                    "correction_u", "iob_applied_u" -> correctionSlack
                    else -> 0.0
                }
                abs(it.legacy - it.next) <= it.tolerance + slack
            }
            if (explained) {
                return matched(ShadowClass.MATCH_WITHIN_TRACE_PRECISION, baseDiffs,
                    "autosens.effective_ratio_rounded_to_three_decimals")
            }
        }
        if (!settingsVerified) {
            reasons += "settings.unverified_for_nutrients"
            return notComparable()
        }
        return ShadowComparison(calculation.reference, ShadowClass.MISMATCH,
            baseDiffs.filterNot { it.equal }.map { "differs.${it.name}" } + notes, baseDiffs)
    }

    public fun compareAll(input: ShadowInput): List<ShadowComparison> = input.calculations.map { compare(it, input.settings) }

    /** Counts per class and per reason. A report built from them carries no clinical value. */
    public fun summary(comparisons: List<ShadowComparison>): ShadowSummary = ShadowSummary(
        total = comparisons.size,
        byClass = ShadowClass.entries.associateWith { klass -> comparisons.count { it.shadowClass == klass } },
        byReason = comparisons.flatMap { c -> c.reasons.map { "${c.shadowClass.name}:$it" } }.groupingBy { it }.eachCount()
            .toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).toMap(),
    )

    private fun rule(text: String, name: String): Input<Quantity> =
        if (Quantity.isCanonical(text)) Input.Provided(Quantity(text)) else Input.Unavailable("shadow.settings.$name.not_representable")

    private fun units(result: CandidateResult, step: CandidateStep) = (result.step(step) as StepOutcome.Computed).units
    private fun fact(result: CandidateResult, step: CandidateStep, key: String) =
        (result.step(step) as StepOutcome.Computed).facts.first { it.key == key }.value
}

public data class ShadowSummary(val total: Int, val byClass: Map<ShadowClass, Int>, val byReason: Map<String, Int>) {
    /** Only unqualified and qualified matches; a record that is not comparable is never one. */
    val matches: Int get() = byClass.filterKeys { it.name.startsWith("MATCH") }.values.sum()
    val comparable: Int get() = total - (byClass[ShadowClass.NOT_COMPARABLE] ?: 0)
}
