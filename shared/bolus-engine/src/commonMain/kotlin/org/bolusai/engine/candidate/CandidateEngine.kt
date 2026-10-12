package org.bolusai.engine.candidate

import org.bolusai.engine.EngineIdentity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Provenance of the candidate rule set (ADR 0024). Observed in Legacy, not approved: [VALIDATION] stays pending and
 * every result is a test result.
 */
public object CandidateRuleSet {
    public const val ID: String = "legacy-backend-core-candidate-v1"
    public const val LEGACY_REPOSITORY: String = "DanielGTdiabetes/bolus_ai"
    public const val LEGACY_SHA: String = "bcac891d85e6fb3e84398c118061e6aeae3f73f1"
    public const val LEGACY_SOURCE: String = "backend/app/services/bolus_engine.py::_calculate_core"
    public const val VALIDATION: String = "pending"

    /** Glucose threshold observed as the Legacy hard stop `bg_mgdl < 70` (LC-007). Candidate, not approved. */
    public const val LEGACY_HYPO_STOP_MG_DL: Double = 70.0

    /** Legacy treats `cr <= 0.1` and `isf <= 5` as invalid and substitutes literals; Next blocks instead. */
    public const val LEGACY_INVALID_CARB_RATIO_AT_OR_BELOW: Double = 0.1
    public const val LEGACY_INVALID_SENSITIVITY_AT_OR_BELOW_MG_DL: Double = 5.0

    /** Legacy features outside this candidate set. They are not applied and the result says so. */
    public val NOT_APPLIED: List<String> = listOf(
        "autosens", "fiber_deduction", "warsaw_fat_protein", "exercise_reduction", "techne_rounding",
        "dual_bolus", "max_iob_ceiling", "stacking_warning", "glucose_staleness",
    )
}

/** Steps of the candidate rule set, in the order of Legacy `_calculate_core`. */
public enum class CandidateStep(public val code: String, public val legacyReference: String) {
    MEAL("meal", "meal_u = eff_carbs / cr"),
    CORRECTION("correction", "corr_u = (bg_mgdl - target_mgdl) / isf"),
    CORRECTION_LIMIT("correction_limit", "if corr_u > max_correction_u: corr_u = max_correction_u"),
    IOB("iob", "upfront_net = max(0, meal_u + min(corr_u, 0) + max(max(corr_u, 0) - iob_u, 0))"),
    ROUNDING("rounding", "_round_step: round(value / step) * step"),
    MAX_BOLUS("max_bolus", "if final_total > max_bolus_u: final_total = max_bolus_u"),
    HYPO_STOP("hypo_stop", "if bg_mgdl < 70: final_total = 0"),
}

/** One named number shown in the breakdown. Keys are stable codes; the UI translates them. */
public data class Fact(public val key: String, public val value: Double)

public sealed interface StepOutcome {
    public data class Computed(public val units: Double, public val facts: List<Fact>) : StepOutcome

    /** Stable causes, never a value. A step that depends on a blocked one is blocked with `depends_on.<step>`. */
    public data class Blocked(public val causes: List<String>) : StepOutcome {
        init { require(causes.isNotEmpty()) { "engine.blocked_without_cause" } }
    }
}

public data class StepResult(public val step: CandidateStep, public val outcome: StepOutcome)

/**
 * Full output: steps, total, warnings, rules not applied, engine and rule set identity and the input fingerprint.
 * [usableForTreatment] is always false.
 */
public data class CandidateResult(
    public val engineVersion: String,
    public val ruleSetId: String,
    public val legacySha: String,
    public val inputFingerprintSha256: String,
    public val steps: List<StepResult>,
    public val total: StepOutcome,
    public val warnings: List<String>,
    public val notApplied: List<String>,
) {
    public val usableForTreatment: Boolean get() = false
    public val ruleValidation: String get() = CandidateRuleSet.VALIDATION

    public fun step(step: CandidateStep): StepOutcome = steps.first { it.step == step }.outcome
}

/**
 * Single implementation of the candidate calculation (ADR 0024). Deterministic and pure: same request, same result.
 * It does not decide freshness, does not convert units and never turns an absent input into zero. An absence
 * blocks only the steps that need it.
 */
public object CandidateEngine {
    public fun calculate(request: CandidateRequest): CandidateResult {
        val warnings = linkedSetOf(Warnings.TEST_ONLY, Warnings.RULES_NOT_APPROVED)
        val profile = request.profile
        val minute = request.localMinuteOfDay

        fun parameter(name: String, segments: (ProfileSnapshot) -> List<SegmentValue>): Input<Quantity> {
            val snapshot = when (profile) {
                is Input.Unavailable -> return profile
                is Input.Provided -> profile.value
            }
            if (!snapshot.dataConfirmed) return Input.Unavailable("profile.unconfirmed")
            val at = when (minute) {
                is Input.Unavailable -> return minute
                is Input.Provided -> minute.value
            }
            val list = segments(snapshot)
            if (!coversDay(list)) return Input.Unavailable("profile.$name.segments_invalid")
            return when (val value = list.first { at >= it.startMinute && at < it.endMinute }.value) {
                is Input.Unavailable -> Input.Unavailable("profile.$name.not_configured")
                is Input.Provided -> value
            }
        }

        /** A profile-wide limit of the confirmed version; the producer's cause travels unchanged when it is absent. */
        fun limit(select: (ProfileSnapshot) -> Input<Quantity>): Input<Quantity> = when (profile) {
            is Input.Unavailable -> profile
            is Input.Provided ->
                if (!profile.value.dataConfirmed) Input.Unavailable("profile.unconfirmed") else select(profile.value)
        }

        val carbRatio = parameter("carb_ratio") { it.carbRatio }
        val sensitivity = parameter("sensitivity") { it.sensitivity }
        val target = parameter("target") { it.target }
        val profileUnit: Input<GlucoseUnitCode> = when (profile) {
            is Input.Unavailable -> profile
            is Input.Provided -> when (val unit = profile.value.glucoseUnit) {
                is Input.Unavailable -> Input.Unavailable("profile.glucose_unit.not_configured")
                is Input.Provided -> unit
            }
        }

        val glucose = request.glucose
        if (glucose is Input.Provided) {
            warnings += Warnings.FRESHNESS_NOT_APPROVED
            if (!glucose.value.measurementTimeVerified) warnings += Warnings.MEASUREMENT_TIME_UNVERIFIED
            if (glucose.value.origin == GlucoseOrigin.DEXCOM_PERMISSION_SET) warnings += Warnings.DEXCOM_NOT_ATTRIBUTED
        }
        if (request.iob is Input.Provided) warnings += Warnings.MANUAL_IOB
        val roundStep = limit { it.roundStep }
        val maxBolus = limit { it.maxBolus }
        val maxCorrection = limit { it.maxCorrection }

        val steps = mutableListOf<StepResult>()

        // A) Meal. Zero grams needs no ratio, as in Legacy (`if eff_carbs > 0`).
        val meal: StepOutcome = run {
            val carbs = when (val c = request.carbs) {
                is Input.Unavailable -> return@run blocked(c.cause)
                is Input.Provided -> c.value.grams.value
            }
            if (carbs == 0.0) return@run StepOutcome.Computed(0.0, listOf(Fact("carbs_g", 0.0)))
            val cr = when (carbRatio) {
                is Input.Unavailable -> return@run blocked(carbRatio.cause)
                is Input.Provided -> carbRatio.value.value
            }
            if (cr <= CandidateRuleSet.LEGACY_INVALID_CARB_RATIO_AT_OR_BELOW) {
                return@run blocked("profile.carb_ratio.invalid_for_candidate")
            }
            StepOutcome.Computed(carbs / cr, listOf(Fact("carbs_g", carbs), Fact("carb_ratio_g_per_u", cr)))
        }
        steps += StepResult(CandidateStep.MEAL, meal)

        // B) Correction. Traced only in mg/dL; nothing is converted.
        val glucoseMgDl: Input<Double> = when (glucose) {
            is Input.Unavailable -> glucose
            is Input.Provided ->
                if (glucose.value.unit != GlucoseUnitCode.MG_DL) Input.Unavailable("glucose.unit_not_traced_for_candidate")
                else Input.Provided(glucose.value.value.value)
        }
        val correction: StepOutcome = run {
            val causes = mutableListOf<String>()
            if (glucoseMgDl is Input.Unavailable) causes += glucoseMgDl.cause
            when (profileUnit) {
                is Input.Unavailable -> causes += profileUnit.cause
                is Input.Provided -> if (profileUnit.value != GlucoseUnitCode.MG_DL) {
                    causes += "profile.glucose_unit.not_traced_for_candidate"
                }
            }
            if (target is Input.Unavailable) causes += target.cause
            if (sensitivity is Input.Unavailable) causes += sensitivity.cause
            if (causes.isNotEmpty()) return@run StepOutcome.Blocked(causes.distinct())
            val bg = (glucoseMgDl as Input.Provided).value
            val tgt = (target as Input.Provided).value.value
            val isf = (sensitivity as Input.Provided).value.value
            if (isf <= CandidateRuleSet.LEGACY_INVALID_SENSITIVITY_AT_OR_BELOW_MG_DL) {
                return@run blocked("profile.sensitivity.invalid_for_candidate")
            }
            StepOutcome.Computed((bg - tgt) / isf,
                listOf(Fact("glucose_mg_dl", bg), Fact("target_mg_dl", tgt), Fact("sensitivity_mg_dl_per_u", isf)))
        }
        steps += StepResult(CandidateStep.CORRECTION, correction)

        val limited: StepOutcome = after(correction, CandidateStep.CORRECTION) { raw ->
            val limit = when (val m = maxCorrection) {
                is Input.Unavailable -> return@after blocked(m.cause)
                is Input.Provided -> m.value.value
            }
            val value = if (raw > limit) limit else raw
            StepOutcome.Computed(value, listOf(Fact("raw_u", raw), Fact("max_correction_u", limit),
                Fact("limited", if (raw > limit) 1.0 else 0.0)))
        }
        steps += StepResult(CandidateStep.CORRECTION_LIMIT, limited)

        // C) IOB only offsets a positive correction; a negative correction reduces the meal component.
        val upfront: StepOutcome = run {
            val causes = mutableListOf<String>()
            if (meal is StepOutcome.Blocked) causes += "depends_on.${CandidateStep.MEAL.code}"
            if (limited is StepOutcome.Blocked) causes += "depends_on.${CandidateStep.CORRECTION_LIMIT.code}"
            val iob = request.iob
            if (iob is Input.Unavailable) causes += iob.cause
            if (causes.isNotEmpty()) return@run StepOutcome.Blocked(causes)
            val mealU = (meal as StepOutcome.Computed).units
            val corrU = (limited as StepOutcome.Computed).units
            val iobU = (iob as Input.Provided).value.units.value
            val positive = max(corrU, 0.0)
            val lowAdjustment = min(corrU, 0.0)
            val afterIob = max(positive - iobU, 0.0)
            val applied = positive - afterIob
            StepOutcome.Computed(max(0.0, mealU + lowAdjustment + afterIob), listOf(
                Fact("iob_u", iobU), Fact("iob_applied_to_correction_u", applied),
                Fact("correction_after_iob_u", afterIob), Fact("low_glucose_adjustment_u", lowAdjustment)))
        }
        steps += StepResult(CandidateStep.IOB, upfront)

        // 6) Rounding. Kotlin round() is half-even, like Python round() in Legacy.
        val rounded: StepOutcome = after(upfront, CandidateStep.IOB) { value ->
            val step = when (val s = roundStep) {
                is Input.Unavailable -> return@after blocked(s.cause)
                is Input.Provided -> s.value.value
            }
            if (step <= 0.0) return@after blocked("round_step.not_positive")
            StepOutcome.Computed(round(value / step) * step, listOf(Fact("unrounded_u", value), Fact("round_step_u", step)))
        }
        steps += StepResult(CandidateStep.ROUNDING, rounded)

        val capped: StepOutcome = after(rounded, CandidateStep.ROUNDING) { value ->
            val limit = when (val m = maxBolus) {
                is Input.Unavailable -> return@after blocked(m.cause)
                is Input.Provided -> m.value.value
            }
            if (value > limit) warnings += Warnings.MAX_BOLUS_LIMITED
            StepOutcome.Computed(if (value > limit) limit else value, listOf(Fact("before_limit_u", value),
                Fact("max_bolus_u", limit), Fact("limited", if (value > limit) 1.0 else 0.0)))
        }
        steps += StepResult(CandidateStep.MAX_BOLUS, capped)

        // Hard stop. Evaluated whenever glucose in mg/dL is known, even if the dose is blocked, so the warning shows.
        val belowHypoStop = (glucoseMgDl as? Input.Provided)?.value?.let { it < CandidateRuleSet.LEGACY_HYPO_STOP_MG_DL }
        if (belowHypoStop == true) warnings += Warnings.BELOW_HYPO_STOP
        val total: StepOutcome = after(capped, CandidateStep.MAX_BOLUS) { value ->
            when (belowHypoStop) {
                null -> blocked((glucoseMgDl as Input.Unavailable).cause)
                true -> StepOutcome.Computed(0.0, listOf(Fact("before_stop_u", value),
                    Fact("threshold_mg_dl", CandidateRuleSet.LEGACY_HYPO_STOP_MG_DL), Fact("applied", 1.0)))
                false -> StepOutcome.Computed(value, listOf(Fact("before_stop_u", value),
                    Fact("threshold_mg_dl", CandidateRuleSet.LEGACY_HYPO_STOP_MG_DL), Fact("applied", 0.0)))
            }
        }
        steps += StepResult(CandidateStep.HYPO_STOP, total)

        return CandidateResult(
            engineVersion = EngineIdentity.VERSION,
            ruleSetId = CandidateRuleSet.ID,
            legacySha = CandidateRuleSet.LEGACY_SHA,
            inputFingerprintSha256 = EngineSha256.hex(CandidateCodec.encode(request).encodeToByteArray()),
            steps = steps,
            total = total,
            warnings = warnings.toList(),
            notApplied = CandidateRuleSet.NOT_APPLIED,
        )
    }

    private fun blocked(cause: String) = StepOutcome.Blocked(listOf(cause))

    private inline fun after(previous: StepOutcome, step: CandidateStep, next: (Double) -> StepOutcome): StepOutcome =
        when (previous) {
            is StepOutcome.Blocked -> blocked("depends_on.${step.code}")
            is StepOutcome.Computed -> next(previous.units)
        }

    private fun coversDay(segments: List<SegmentValue>): Boolean =
        segments.isNotEmpty() && segments.first().startMinute == 0 &&
            segments.last().endMinute == CandidateRequest.MINUTES_PER_DAY &&
            segments.all { it.endMinute > it.startMinute } &&
            segments.zipWithNext().all { (a, b) -> a.endMinute == b.startMinute }
}

/** Stable warning codes. They never carry values. */
public object Warnings {
    public const val TEST_ONLY: String = "result.test_only_not_for_treatment"
    public const val RULES_NOT_APPROVED: String = "rules.candidate_validation_pending"
    public const val FRESHNESS_NOT_APPROVED: String = "glucose.freshness_policy_not_approved"
    public const val MEASUREMENT_TIME_UNVERIFIED: String = "glucose.measurement_time_unverified"
    public const val DEXCOM_NOT_ATTRIBUTED: String = "glucose.sender_not_attributed_to_dexcom"
    public const val MANUAL_IOB: String = "iob.manual_entry_unverified"
    public const val MAX_BOLUS_LIMITED: String = "max_bolus.limited"
    public const val BELOW_HYPO_STOP: String = "glucose.below_legacy_hypo_stop"
}

/** Canonical text of a request: the input fingerprint covers every value, unit, origin, instant and version. */
public object CandidateCodec {
    private const val MISSING = "~"

    public fun encode(request: CandidateRequest): String = buildString {
        fun line(key: String, value: String) { append(key).append('=').append(value).append('\n') }
        fun <T> input(key: String, input: Input<T>, render: (T) -> String) = line(key, when (input) {
            is Input.Unavailable -> "$MISSING${input.cause}"
            is Input.Provided -> render(input.value)
        })
        fun segments(key: String, list: List<SegmentValue>) = list.forEach { segment ->
            input("$key.${segment.startMinute}-${segment.endMinute}", segment.value) { it.text }
        }
        line("codec", "bolus-ai-next/candidate-request/v2")
        line("engine", EngineIdentity.VERSION)
        line("rule_set", CandidateRuleSet.ID)
        line("calculation_at_ms", request.calculationAtEpochMs.toString())
        input("local_minute", request.localMinuteOfDay) { it.toString() }
        input("profile", request.profile) { "v${it.version}:${it.fingerprintSha256}:confirmed=${it.dataConfirmed}" }
        (request.profile as? Input.Provided)?.value?.let { profile ->
            input("profile.glucose_unit", profile.glucoseUnit) { it.code }
            input("profile.time_zone", profile.timeZoneId) { it }
            segments("profile.carb_ratio", profile.carbRatio)
            segments("profile.sensitivity", profile.sensitivity)
            segments("profile.target", profile.target)
            input("profile.round_step", profile.roundStep) { it.text }
            input("profile.max_bolus", profile.maxBolus) { it.text }
            input("profile.max_correction", profile.maxCorrection) { it.text }
        }
        input("carbs", request.carbs) { "${it.grams.text}:${it.origin.code}" }
        input("glucose", request.glucose) {
            "${it.value.text}:${it.unit.code}:${it.origin.code}:${it.recordedAtEpochMs}:verified=${it.measurementTimeVerified}" +
                ":source=${it.sourceReference}"
        }
        input("iob", request.iob) { "${it.units.text}:${it.origin}" }
    }
}
