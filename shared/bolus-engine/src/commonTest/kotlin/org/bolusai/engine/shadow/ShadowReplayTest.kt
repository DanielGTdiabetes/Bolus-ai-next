package org.bolusai.engine.shadow

import org.bolusai.engine.candidate.LEGACY_VECTORS
import org.bolusai.engine.candidate.NUTRITION_VECTORS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ADR 0030: shadow replay of stored Legacy calculations. Synthetic records only, built from the Legacy vectors. */
class ShadowReplayTest {
    private val hash = "c0ffee"
    private val settings = LegacyRuleSettings(hash, true, "0.5", "5", true, "0.1", "0.2", "300")

    /** 60 g at 10 g/U, 190 mg/dL against 110 at 40: meal 6 U, correction 2 U, total 8 U. */
    private fun record(vararg changes: Pair<String, String?>): LegacyCalculation {
        val fields = linkedMapOf<String, String?>("carbs" to "60", "fiber" to "0", "fat" to "0", "protein" to "0",
            "cr" to "10", "isf" to "40", "target" to "110", "glucose" to "190", "glucose_stale" to "false", "iob" to "0.0",
            "round_step" to "0.1", "max_bolus" to "15", "max_correction" to "5", "autosens_ratio" to "1.0",
            "techne_enabled" to "false", "exercise" to "false", "config_hash" to hash, "expected.meal_u" to "6.0",
            "expected.correction_u" to "2.0", "expected.iob_applied_u" to "0.0", "expected.total_u" to "8.0")
        changes.forEach { (key, value) -> fields[key] = value }
        @Suppress("UNCHECKED_CAST")
        return LegacyCalculation("r1", fields.filterValues { it != null } as Map<String, String>)
    }

    private fun compare(vararg changes: Pair<String, String?>, with: LegacyRuleSettings = settings) =
        ShadowReplay.compare(record(*changes), with)

    @Test fun everyLegacyVectorReplaysAsAMatch() {
        for (v in LEGACY_VECTORS) {
            val result = ShadowReplay.compare(LegacyCalculation(v.name, mapOf("carbs" to v.carbs, "fiber" to "0", "fat" to "0",
                "protein" to "0", "cr" to v.carbRatio, "isf" to v.sensitivity, "target" to v.target, "glucose" to v.glucose,
                "glucose_stale" to "false", "iob" to v.iob, "round_step" to v.roundStep, "max_bolus" to v.maxBolus,
                "max_correction" to v.maxCorrection, "autosens_ratio" to "1.0", "techne_enabled" to "false",
                "exercise" to "false", "expected.meal_u" to v.mealU.toString(),
                "expected.correction_u" to v.correctionU.toString(), "expected.iob_applied_u" to v.iobAppliedU.toString(),
                "expected.total_u" to v.totalU.toString())), settings.copy(configHash = null))
            assertEquals(ShadowClass.MATCH, result.shadowClass, v.name)
        }
        for (v in NUTRITION_VECTORS) {
            val own = LegacyRuleSettings(hash, true, v.fiberFactor, v.fiberThreshold, true, v.warsawSimple, v.warsawDual,
                v.warsawTrigger)
            val result = ShadowReplay.compare(LegacyCalculation(v.name, mapOf("carbs" to v.carbs, "fiber" to v.fiber,
                "fat" to v.fat, "protein" to v.protein, "cr" to v.carbRatio, "isf" to v.sensitivity, "target" to v.target,
                "glucose" to v.glucose, "glucose_stale" to "false", "iob" to v.iob, "round_step" to v.roundStep,
                "max_bolus" to v.maxBolus, "max_correction" to v.maxCorrection, "autosens_ratio" to "1.0",
                "techne_enabled" to "false", "exercise" to "false", "config_hash" to hash,
                "expected.meal_u" to v.mealU.toString(), "expected.correction_u" to v.correctionU.toString(),
                "expected.iob_applied_u" to v.iobAppliedU.toString(), "expected.total_u" to v.totalU.toString())), own)
            val expected = if (v.strategy == "normal") ShadowClass.MATCH_STRATEGY_INFERRED else ShadowClass.MATCH
            assertEquals(expected, result.shadowClass, v.name)
        }
    }

    @Test fun anEquivalentRecordMatchesAndCarriesItsComponents() {
        val result = compare()
        assertEquals(ShadowClass.MATCH, result.shadowClass)
        assertEquals(listOf("meal_u", "correction_u", "iob_applied_u", "total_u"), result.components.map { it.name })
        assertTrue(result.components.all { it.equal })
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun whatLegacyAppliedAndNextDoesNotIsNeverAMatch() {
        val cases = mapOf(
            compare("glucose" to null) to "legacy.glucose_absent",
            compare("glucose_stale" to "true") to "legacy.glucose_stale",
            compare("techne_enabled" to "true", "trend" to "SingleUp") to "legacy.techne_rounding_possible",
            compare("exercise" to "true") to "trace.missing.exercise_minutes",
            compare("exercise" to "true", "exercise_minutes" to "60", "exercise_intensity" to "extreme") to
                "legacy.exercise_intensity_not_replayable",
            compare("max_iob" to "5") to "legacy.max_iob_ceiling_applies",
            compare("carbs" to null) to "trace.missing.carbs",
            compare("expected.total_u" to null) to "trace.missing.expected.total_u",
            compare("glucose_stale" to null) to "trace.missing.glucose_stale",
            compare("exercise" to null) to "trace.missing.exercise",
            compare("carbs" to "12.3456") to "value.not_representable.carbs",
            compare("iob" to "-1") to "trace.negative_iob",
        )
        for ((result, reason) in cases) {
            assertEquals(ShadowClass.NOT_COMPARABLE, result.shadowClass, reason)
            assertTrue(reason in result.reasons, "$reason in ${result.reasons}")
            assertTrue(result.components.isEmpty())
        }
    }

    @Test fun autosensIsReplayedWithTheAdjustedRatiosTheTraceStores() {
        // Ratio 1.1: Legacy divided 10 g/U and 40 mg/dL/U and stored 9.091 and 36.364. Meal 6.6 U, correction 2.2 U.
        val result = compare("autosens_ratio" to "1.1", "cr" to "9.091", "isf" to "36.364", "expected.meal_u" to "6.6",
            "expected.correction_u" to "2.2", "expected.total_u" to "8.8")
        assertEquals(ShadowClass.MATCH, result.shadowClass)
        assertEquals(listOf("autosens.effective_ratios_replayed"), result.reasons)
        val wrong = compare("autosens_ratio" to "1.1", "cr" to "9.091", "isf" to "36.364", "expected.total_u" to "8.0")
        assertEquals(ShadowClass.MISMATCH, wrong.shadowClass)
        assertTrue("autosens.effective_ratios_replayed" in wrong.reasons)
    }

    @Test fun theThreeDecimalsOfAnAdjustedRatioExplainAComponentButNeverATotal() {
        // Ratio 4 g/U with autosens 0.9 is 4.4444..., stored as 4.444. Legacy: 17.8 / 4.4444 = 4.005 -> stored 4.0.
        // Replayed with 4.444 the meal is 4.0054: 0.0054 from the stored value, with the same 4.0 U total.
        val adjusted = arrayOf<Pair<String, String?>>("autosens_ratio" to "0.9", "carbs" to "17.8", "cr" to "4.444",
            "glucose" to "110", "round_step" to "0.5", "expected.meal_u" to "4.0", "expected.correction_u" to "0.0")
        val result = compare(*adjusted, "expected.total_u" to "4.0")
        assertEquals(ShadowClass.MATCH_WITHIN_TRACE_PRECISION, result.shadowClass)
        assertEquals(listOf("autosens.effective_ratio_rounded_to_three_decimals", "autosens.effective_ratios_replayed"),
            result.reasons)
        // A different total is never explained by the ratio.
        assertEquals(ShadowClass.MISMATCH, compare(*adjusted, "expected.total_u" to "4.5").shadowClass)
        // Without autosens the ratio is exact, so the same component difference is a mismatch.
        assertEquals(ShadowClass.MISMATCH, compare(*adjusted, "autosens_ratio" to "1.0", "expected.total_u" to "4.0").shadowClass)
        // A component further away than the three decimals allow stays a mismatch.
        assertEquals(ShadowClass.MISMATCH, compare(*adjusted, "expected.meal_u" to "4.02", "expected.total_u" to "4.0").shadowClass)
    }

    @Test fun exerciseIsReplayedFromTheMinutesAndIntensityOfTheBreakdown() {
        // 8 U reduced 15 % by 60 min of low intensity: 6.8 U.
        val exercise = arrayOf<Pair<String, String?>>("exercise" to "true", "exercise_minutes" to "60",
            "exercise_intensity" to "low")
        val result = compare(*exercise, "expected.total_u" to "6.8")
        assertEquals(ShadowClass.MATCH, result.shadowClass)
        assertEquals(listOf("exercise.reduction_replayed"), result.reasons)
        assertEquals(ShadowClass.MISMATCH, compare(*exercise).shadowClass)
        // The IOB ceiling is checked against the dose before the reduction, as Legacy does.
        assertEquals(ShadowClass.NOT_COMPARABLE, compare(*exercise, "expected.total_u" to "6.8", "max_iob" to "7").shadowClass)
    }

    @Test fun whatDoesNotChangeTheDoseStaysComparable() {
        assertEquals(ShadowClass.MATCH, compare("techne_enabled" to "true").shadowClass)
        assertEquals(ShadowClass.MATCH, compare("max_iob" to "20").shadowClass)
        assertEquals(ShadowClass.MATCH, compare("max_iob" to "0").shadowClass)
    }

    @Test fun anUnexplainedDifferenceIsAMismatchWithItsComponents() {
        val result = compare("expected.total_u" to "8.2")
        assertEquals(ShadowClass.MISMATCH, result.shadowClass)
        assertEquals(listOf("differs.total_u"), result.reasons)
        assertEquals(8.0, result.components.first { it.name == "total_u" }.next)
        assertEquals(listOf("differs.meal_u"), compare("expected.meal_u" to "6.2").reasons)
    }

    @Test fun nutrientsNeedTheSettingsThatWereInForce() {
        // 20 g fat + 30 g protein = 300 kcal: dual factor 0.2 -> 0.6 U extended, total 8.6 U.
        val warsaw = arrayOf<Pair<String, String?>>("fat" to "20", "protein" to "30", "expected.total_u" to "8.6")
        assertEquals(ShadowClass.MATCH, compare(*warsaw).shadowClass)
        val other = compare(*warsaw, "config_hash" to "another")
        assertEquals(ShadowClass.MATCH_SETTINGS_UNVERIFIED, other.shadowClass)
        assertEquals(listOf("settings.unverified_for_nutrients"), other.reasons)
        assertEquals(ShadowClass.MATCH_SETTINGS_UNVERIFIED, compare(*warsaw, with = settings.copy(configHash = null)).shadowClass)
        // A difference with unverified settings cannot be blamed on a rule: not comparable, never a mismatch or match.
        val unknown = compare(*warsaw, "config_hash" to "another", "expected.total_u" to "9.4")
        assertEquals(ShadowClass.NOT_COMPARABLE, unknown.shadowClass)
        assertEquals(listOf("settings.unverified_for_nutrients"), unknown.reasons)
        assertEquals(ShadowClass.MISMATCH, compare(*warsaw, "expected.total_u" to "9.4").shadowClass)
        // Without nutrients the rules are not read, so their settings do not matter.
        assertEquals(ShadowClass.MATCH, compare("config_hash" to "another").shadowClass)
    }

    @Test fun aSwitchThatIsOffReplaysAsAFactorOfZero() {
        val off = settings.copy(warsawEnabled = false, fiberDeduction = false)
        assertEquals(ShadowClass.MATCH, compare("fat" to "20", "protein" to "30", "fiber" to "12", with = off).shadowClass)
    }

    @Test fun theNormalStrategyIsInferredOnlyWhenItExplainsTheResult() {
        // Simple factor 0.1 on 300 kcal: 0.3 U in the meal, total 8.3 U.
        val result = compare("fat" to "20", "protein" to "30", "expected.meal_u" to "6.3", "expected.total_u" to "8.3")
        assertEquals(ShadowClass.MATCH_STRATEGY_INFERRED, result.shadowClass)
        assertEquals(listOf("warsaw_strategy.normal_inferred"), result.reasons)
    }

    @Test fun theTwoDecimalsOfTheStoredIobExplainADifferenceInsideTheirInterval() {
        // Legacy used 0.004 U of IOB and stored 0.0: with a 0.001 U step its total was 7.996 U.
        val result = compare("round_step" to "0.001", "expected.total_u" to "7.996")
        assertEquals(ShadowClass.MATCH_WITHIN_TRACE_PRECISION, result.shadowClass)
        assertEquals(listOf("iob.trace_rounded_to_two_decimals"), result.reasons)
        // Outside the interval (8.000 down to 7.995) it is a mismatch.
        assertEquals(ShadowClass.MISMATCH, compare("round_step" to "0.001", "expected.total_u" to "7.99").shadowClass)
    }

    @Test fun theSummaryCountsClassesAndReasonsWithoutValues() {
        val summary = ShadowReplay.summary(listOf(compare(), compare(), compare("glucose_stale" to "true"),
            compare("expected.total_u" to "8.2")))
        assertEquals(4, summary.total)
        assertEquals(2, summary.byClass[ShadowClass.MATCH])
        assertEquals(1, summary.byClass[ShadowClass.MISMATCH])
        assertEquals(1, summary.byClass[ShadowClass.NOT_COMPARABLE])
        assertEquals(2, summary.matches)
        assertEquals(3, summary.comparable)
        assertEquals(mapOf("MISMATCH:differs.total_u" to 1, "NOT_COMPARABLE:legacy.glucose_stale" to 1), summary.byReason)
    }

    @Test fun theMinimisedTextIsParsedStrictly() {
        val text = listOf("format=${ShadowReplay.FORMAT}", "legacy_sha=abc", "settings.config_hash=~",
            "settings.config_hash_source=none", "settings.fiber_deduction=false", "settings.fiber_factor=0.5",
            "settings.fiber_threshold=5", "settings.warsaw_enabled=true", "settings.warsaw_factor_simple=0.1",
            "settings.warsaw_factor_dual=0.2", "settings.warsaw_trigger=300", "", "record=r1", "carbs=60", "glucose=~", "",
            "record=r2", "carbs=10").joinToString("\n") + "\n"
        val input = ShadowReplay.parse(text)
        assertEquals("abc", input.legacySha)
        assertEquals(LegacyRuleSettings(null, false, "0.5", "5", true, "0.1", "0.2", "300"), input.settings)
        assertEquals(listOf(LegacyCalculation("r1", mapOf("carbs" to "60")), LegacyCalculation("r2", mapOf("carbs" to "10"))),
            input.calculations)
        assertEquals(input, ShadowReplay.parse(text.replace("\n", "\r\n")))
        assertFailsWith<IllegalArgumentException> { ShadowReplay.parse(text.replace(ShadowReplay.FORMAT, "other")) }
        assertFailsWith<IllegalArgumentException> { ShadowReplay.parse(text.replace("record=r2", "record=r1")) }
        assertFailsWith<IllegalArgumentException> { ShadowReplay.parse(text.replace("carbs=10", "carbs")) }
        assertFailsWith<IllegalArgumentException> { ShadowReplay.parse(text.replace("settings.warsaw_trigger=300\n", "")) }
    }
}
