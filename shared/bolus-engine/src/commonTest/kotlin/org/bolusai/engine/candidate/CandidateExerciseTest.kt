package org.bolusai.engine.candidate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR 0031: exercise reduction in the candidate engine. Synthetic inputs only. */
class CandidateExerciseTest {
    private fun q(text: String) = Quantity(text)
    private fun units(outcome: StepOutcome) = (outcome as StepOutcome.Computed).units
    private fun causes(outcome: StepOutcome) = (outcome as StepOutcome.Blocked).causes
    private fun fact(result: CandidateResult, step: CandidateStep, key: String) =
        (result.step(step) as StepOutcome.Computed).facts.first { it.key == key }.value
    private fun planned(minutes: Int, intensity: ExerciseIntensity): Input<ExercisePlan> =
        Input.Provided(ExercisePlan.Planned(minutes, intensity))

    private fun run(v: ExerciseVector, exercise: Input<ExercisePlan>) = CandidateEngine.calculate(request(
        profile = Input.Provided(profile(v.carbRatio, v.sensitivity, v.target, roundStep = v.roundStep, maxBolus = v.maxBolus,
            nutritionRules = NutritionRules(Input.Provided(q("0")), Input.Provided(q("0")),
                Input.Provided(q(EXERCISE_VECTORS_WARSAW_SIMPLE)), Input.Provided(q(EXERCISE_VECTORS_WARSAW_DUAL)),
                Input.Provided(q(EXERCISE_VECTORS_WARSAW_TRIGGER))))),
        carbs = v.carbs,
        glucose = Input.Provided(GlucoseValue(q(v.glucose), GlucoseUnitCode.MG_DL, GlucoseOrigin.MANUAL_ENTRY, 0L, false,
            "manual")),
        iob = v.iob,
        nutrition = MealNutrition(Input.Provided(q("0")), Input.Provided(q(v.fat)), Input.Provided(q(v.protein))),
        exercise = exercise,
    ))

    @Test
    fun matchesEveryExerciseVectorCapturedFromLegacyExactly() {
        assertEquals(CandidateRuleSet.LEGACY_SHA, EXERCISE_VECTORS_SHA)
        assertTrue(EXERCISE_VECTORS.size >= 20)
        for (v in EXERCISE_VECTORS) {
            val intensity = ExerciseIntensity.entries.first { it.code == v.intensity }
            val result = run(v, planned(v.minutes, intensity))
            assertEquals(v.mealU, units(result.step(CandidateStep.MEAL)), v.name)
            assertEquals(v.correctionU, units(result.step(CandidateStep.CORRECTION_LIMIT)), v.name)
            assertEquals(v.totalU, units(result.total), v.name)
            val reduced = fact(result, CandidateStep.MAX_BOLUS, "limited") == 1.0 ||
                fact(result, CandidateStep.HYPO_STOP, "applied") == 1.0
            if (!reduced) assertEquals(v.extendedU, fact(result, CandidateStep.ROUNDING, "rounded_extended_u"), v.name)
            // The same case without exercise, and with exercise planned for 0 minutes, is the unreduced Legacy total.
            assertEquals(v.totalWithoutExerciseU, units(run(v, Input.Provided(ExercisePlan.NotPlanned)).total), v.name)
            assertEquals(v.totalWithoutExerciseU, units(run(v, planned(0, intensity)).total), v.name)
            assertFalse(result.usableForTreatment, v.name)
        }
    }

    @Test
    fun theReductionFollowsTheLegacyTable() {
        assertEquals(0.0, CandidateEngine.exerciseReduction(0, ExerciseIntensity.HIGH))
        assertEquals(0.15, CandidateEngine.exerciseReduction(60, ExerciseIntensity.LOW))
        assertEquals(0.30, CandidateEngine.exerciseReduction(120, ExerciseIntensity.LOW))
        assertEquals(0.30, CandidateEngine.exerciseReduction(60, ExerciseIntensity.MODERATE))
        assertEquals(0.55, CandidateEngine.exerciseReduction(120, ExerciseIntensity.MODERATE))
        assertEquals(0.45, CandidateEngine.exerciseReduction(60, ExerciseIntensity.HIGH))
        assertEquals(0.75, CandidateEngine.exerciseReduction(120, ExerciseIntensity.HIGH))
        assertEquals(0.75, CandidateEngine.exerciseReduction(600, ExerciseIntensity.HIGH))
        assertTrue(ExerciseIntensity.entries.all { CandidateEngine.exerciseReduction(100_000, it) <=
            CandidateRuleSet.LEGACY_EXERCISE_MAX_REDUCTION })
    }

    @Test
    fun exerciseIsAStepOfItsOwnBetweenIobAndRounding() {
        // 60 g at 10 g/U and 190 against 110 at 40: 8 U. Moderate for 60 min reduces 30 %: 5.6 U.
        val result = CandidateEngine.calculate(request(exercise = planned(60, ExerciseIntensity.MODERATE)))
        val order = result.steps.map { it.step }
        assertEquals(order.indexOf(CandidateStep.IOB) + 1, order.indexOf(CandidateStep.EXERCISE))
        assertEquals(order.indexOf(CandidateStep.EXERCISE) + 1, order.indexOf(CandidateStep.ROUNDING))
        assertEquals(8.0, units(result.step(CandidateStep.IOB)))
        assertEquals(5.6, units(result.step(CandidateStep.EXERCISE)), 1e-12)
        assertEquals(0.30, fact(result, CandidateStep.EXERCISE, "exercise_reduction"))
        assertEquals(5.6, units(result.total), 1e-12)
        assertFalse("exercise_reduction" in result.notApplied)
        val none = CandidateEngine.calculate(request())
        assertEquals(0.0, fact(none, CandidateStep.EXERCISE, "exercise_planned"))
        assertEquals(8.0, units(none.total))
    }

    @Test
    fun aPlannedExerciseWithUnknownDataBlocksInsteadOfCountingAsNone() {
        val result = CandidateEngine.calculate(request(exercise = Input.Unavailable("exercise.minutes.missing")))
        assertEquals(listOf("exercise.minutes.missing"), causes(result.step(CandidateStep.EXERCISE)))
        assertEquals(listOf("depends_on.exercise"), causes(result.step(CandidateStep.ROUNDING)))
        assertTrue(result.total is StepOutcome.Blocked)
        // The components before it are still computed and shown.
        assertEquals(8.0, units(result.step(CandidateStep.IOB)))
        assertFailsWith<IllegalArgumentException> { ExercisePlan.Planned(-1, ExerciseIntensity.LOW) }
    }

    @Test
    fun theFingerprintCoversTheExercise() {
        val base = CandidateEngine.calculate(request()).inputFingerprintSha256
        listOf(planned(60, ExerciseIntensity.LOW), planned(61, ExerciseIntensity.LOW), planned(60, ExerciseIntensity.HIGH),
            planned(0, ExerciseIntensity.LOW), Input.Unavailable("exercise.minutes.missing")).map {
            CandidateEngine.calculate(request(exercise = it)).inputFingerprintSha256
        }.let { all -> assertEquals(all.size + 1, (all + base).toSet().size) }
        assertNotEquals(base, CandidateEngine.calculate(request(exercise = planned(60, ExerciseIntensity.LOW))).inputFingerprintSha256)
    }
}
