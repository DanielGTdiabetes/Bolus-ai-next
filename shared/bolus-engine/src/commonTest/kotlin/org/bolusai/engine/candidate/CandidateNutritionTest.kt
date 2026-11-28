package org.bolusai.engine.candidate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR 0029: fibre deduction and Warsaw in the candidate engine. Synthetic inputs only. */
class CandidateNutritionTest {
    private fun q(text: String) = Quantity(text)
    private fun given(text: String?, cause: String): Input<Quantity> =
        if (text == null) Input.Unavailable(cause) else Input.Provided(q(text))

    private fun rules(fiberFactor: String? = "0.5", fiberThreshold: String? = "5", simple: String? = "0.1",
                      dual: String? = "0.2", trigger: String? = "300") = NutritionRules(
        given(fiberFactor, "profile.fiber_factor.not_configured"),
        given(fiberThreshold, "profile.fiber_threshold.not_configured"),
        given(simple, "profile.warsaw_factor_simple.not_configured"),
        given(dual, "profile.warsaw_factor_dual.not_configured"),
        given(trigger, "profile.warsaw_trigger.not_configured"))

    private fun meal(fiber: String? = "0", fat: String? = "0", protein: String? = "0") = MealNutrition(
        given(fiber, "meal.fiber.missing"), given(fat, "meal.fat.missing"), given(protein, "meal.protein.missing"))

    /** 60 g at 10 g/U and glucose on target: 6 U without any nutrition rule. */
    private fun calculate(rules: NutritionRules = rules(), meal: MealNutrition = meal(), carbs: String? = "60",
                          confirmed: Boolean = true, strategy: WarsawStrategy = WarsawStrategy.AUTO) =
        CandidateEngine.calculate(request(profile = Input.Provided(profile(target = "190", nutritionRules = rules,
            confirmed = confirmed)), carbs = carbs, nutrition = meal, strategy = strategy))

    private fun units(outcome: StepOutcome) = (outcome as StepOutcome.Computed).units
    private fun causes(outcome: StepOutcome) = (outcome as StepOutcome.Blocked).causes
    private fun fact(result: CandidateResult, step: CandidateStep, key: String) =
        (result.step(step) as StepOutcome.Computed).facts.first { it.key == key }.value

    @Test
    fun matchesEveryNutritionVectorCapturedFromLegacyExactly() {
        assertEquals(CandidateRuleSet.LEGACY_SHA, NUTRITION_VECTORS_SHA)
        assertTrue(NUTRITION_VECTORS.size >= 20)
        for (v in NUTRITION_VECTORS) {
            val result = CandidateEngine.calculate(request(
                profile = Input.Provided(profile(v.carbRatio, v.sensitivity, v.target, roundStep = v.roundStep,
                    maxBolus = v.maxBolus, maxCorrection = v.maxCorrection, nutritionRules = NutritionRules(
                        Input.Provided(q(v.fiberFactor)), Input.Provided(q(v.fiberThreshold)),
                        Input.Provided(q(v.warsawSimple)), Input.Provided(q(v.warsawDual)),
                        Input.Provided(q(v.warsawTrigger))))),
                carbs = v.carbs,
                glucose = Input.Provided(GlucoseValue(q(v.glucose), GlucoseUnitCode.MG_DL, GlucoseOrigin.MANUAL_ENTRY,
                    0L, false, "manual")),
                iob = v.iob,
                nutrition = MealNutrition(Input.Provided(q(v.fiber)), Input.Provided(q(v.fat)), Input.Provided(q(v.protein))),
                strategy = WarsawStrategy.entries.first { it.code == v.strategy },
            ))
            assertEquals(v.mealU, units(result.step(CandidateStep.MEAL)), v.name)
            assertEquals(v.correctionU, units(result.step(CandidateStep.CORRECTION_LIMIT)), v.name)
            assertEquals(v.iobAppliedU, fact(result, CandidateStep.IOB, "iob_applied_to_correction_u"), v.name)
            assertEquals(v.totalU, units(result.total), v.name)
            // Legacy reports the rounded later part only before the maximum bolus and the hypo stop reduce it.
            val reduced = fact(result, CandidateStep.MAX_BOLUS, "limited") == 1.0 ||
                fact(result, CandidateStep.HYPO_STOP, "applied") == 1.0
            if (!reduced) assertEquals(v.extendedU, fact(result, CandidateStep.ROUNDING, "rounded_extended_u"), v.name)
            assertFalse(result.usableForTreatment, v.name)
        }
    }

    @Test
    fun nutritionRulesAreNoLongerListedAsNotApplied() {
        val notApplied = calculate().notApplied
        assertFalse("fiber_deduction" in notApplied)
        assertFalse("warsaw_fat_protein" in notApplied)
        assertTrue("dual_bolus" in notApplied)
    }

    @Test
    fun anUnknownNutrientBlocksTheMealInsteadOfCountingAsZero() {
        val result = calculate(meal = MealNutrition.UNKNOWN)
        assertEquals(listOf("meal.fiber.missing", "meal.fat.missing", "meal.protein.missing"),
            causes(result.step(CandidateStep.MEAL)))
        assertEquals(listOf("depends_on.meal"), causes(result.step(CandidateStep.EXTENDED)))
        assertTrue(result.total is StepOutcome.Blocked)
        assertEquals(listOf("meal.fat.missing"), causes(calculate(meal = meal(fat = null)).step(CandidateStep.MEAL)))
    }

    @Test
    fun aFactorOfZeroSwitchesTheRuleOffAndItsNutrientIsNotRead() {
        val off = rules(fiberFactor = "0", simple = "0", dual = "0")
        assertEquals(6.0, units(calculate(off, MealNutrition.UNKNOWN).total))
        // Only fibre off: fat and protein are still required.
        assertEquals(listOf("meal.fat.missing", "meal.protein.missing"),
            causes(calculate(rules(fiberFactor = "0"), MealNutrition.UNKNOWN).step(CandidateStep.MEAL)))
        // Only Warsaw off: fibre is still required.
        assertEquals(listOf("meal.fiber.missing"),
            causes(calculate(rules(simple = "0", dual = "0"), MealNutrition.UNKNOWN).step(CandidateStep.MEAL)))
    }

    @Test
    fun declaredZeroNutrientsNeedNoRulesAtAll() {
        assertEquals(6.0, units(calculate(NutritionRules.NOT_IN_SCHEMA, meal()).total))
        // Without carbohydrates the fibre is never read, but fat and protein still are.
        assertEquals(listOf("meal.fat.missing", "meal.protein.missing"),
            causes(calculate(NutritionRules.NOT_IN_SCHEMA, MealNutrition.UNKNOWN, carbs = "0").step(CandidateStep.MEAL)))
        assertEquals(0.0, units(calculate(NutritionRules.NOT_IN_SCHEMA, meal(fiber = null), carbs = "0")
            .step(CandidateStep.MEAL)))
    }

    @Test
    fun rulesMissingFromTheProfileBlockOnlyWhenTheyWouldBeRead() {
        val none = NutritionRules.NOT_IN_SCHEMA
        assertEquals(listOf("profile.fiber_factor.not_in_schema", "profile.fiber_threshold.not_in_schema"),
            causes(calculate(none, meal(fiber = "12")).step(CandidateStep.MEAL)))
        // Fibre at or above the carbohydrates is never deducted, so no rule is read.
        assertEquals(6.0, units(calculate(none, meal(fiber = "60")).total))
        assertEquals(listOf("profile.warsaw_trigger.not_in_schema"),
            causes(calculate(none, meal(fat = "20", protein = "30")).step(CandidateStep.MEAL)))
        // At or below 50 kcal Legacy applies nothing, so no rule is read.
        assertEquals(6.0, units(calculate(none, meal(protein = "12.5")).total))
        assertEquals(listOf("profile.warsaw_factor_simple.not_configured"),
            causes(calculate(rules(simple = null), meal(fat = "10", protein = "20")).step(CandidateStep.MEAL)))
        assertEquals(listOf("profile.warsaw_factor_dual.not_configured"),
            causes(calculate(rules(dual = null), meal(fat = "20", protein = "30")).step(CandidateStep.MEAL)))
        assertEquals(listOf("profile.fiber_threshold.not_configured"),
            causes(calculate(rules(fiberThreshold = null), meal(fiber = "12")).step(CandidateStep.MEAL)))
    }

    @Test
    fun theNormalStrategyNeverReadsTheTriggerOrTheDualFactor() {
        val result = calculate(rules(dual = null, trigger = null), meal(fat = "20", protein = "30"),
            strategy = WarsawStrategy.NORMAL)
        // 300 kcal = 3 FPU x 10 g x 0.1 = 3 g at 10 g/U = 0.3 U on top of 6 U.
        assertEquals(6.3, units(result.step(CandidateStep.MEAL)), 1e-12)
        assertEquals(0.0, units(result.step(CandidateStep.EXTENDED)))
    }

    @Test
    fun anUnconfirmedProfileBlocksTheRules() {
        assertEquals(listOf("profile.unconfirmed"),
            causes(calculate(meal = meal(fiber = "12"), confirmed = false).step(CandidateStep.MEAL)))
    }

    @Test
    fun warsawWithoutCarbohydratesStillNeedsTheCarbRatio() {
        val result = CandidateEngine.calculate(request(profile = Input.Provided(profile(carbRatio = null, target = "190",
            nutritionRules = rules())), carbs = "0", nutrition = meal(fat = "10", protein = "10")))
        assertEquals(listOf("profile.carb_ratio.not_configured"), causes(result.step(CandidateStep.MEAL)))
    }

    @Test
    fun theExtendedPartIsAStepOfItsOwnAndIsAddedToTheTotal() {
        val result = calculate(meal = meal(fat = "20", protein = "30"))
        assertEquals(6.0, units(result.step(CandidateStep.MEAL)))
        // 3 FPU x 10 g x 0.2 = 6 g at 10 g/U.
        assertEquals(0.6, units(result.step(CandidateStep.EXTENDED)), 1e-12)
        assertEquals(6.6, units(result.total), 1e-12)
    }

    @Test
    fun theFingerprintCoversNutrientsRulesAndStrategy() {
        val base = calculate(meal = meal(fiber = "12", fat = "20", protein = "30")).inputFingerprintSha256
        val variants = listOf(
            calculate(meal = meal(fiber = "11", fat = "20", protein = "30")),
            calculate(meal = meal(fiber = "12", fat = "21", protein = "30")),
            calculate(meal = meal(fiber = "12", fat = "20", protein = "31")),
            calculate(meal = meal(fiber = null, fat = "20", protein = "30")),
            calculate(rules(fiberFactor = "0.4"), meal(fiber = "12", fat = "20", protein = "30")),
            calculate(rules(fiberThreshold = "6"), meal(fiber = "12", fat = "20", protein = "30")),
            calculate(rules(simple = "0.2"), meal(fiber = "12", fat = "20", protein = "30")),
            calculate(rules(dual = "0.3"), meal(fiber = "12", fat = "20", protein = "30")),
            calculate(rules(trigger = "301"), meal(fiber = "12", fat = "20", protein = "30")),
            calculate(meal = meal(fiber = "12", fat = "20", protein = "30"), strategy = WarsawStrategy.NORMAL),
        )
        variants.forEach { assertNotEquals(base, it.inputFingerprintSha256) }
    }
}
