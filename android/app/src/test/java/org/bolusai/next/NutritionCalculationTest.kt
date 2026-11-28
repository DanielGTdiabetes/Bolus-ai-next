package org.bolusai.next

import org.bolusai.engine.candidate.CandidateStep
import org.bolusai.engine.candidate.ExerciseIntensity
import org.bolusai.engine.candidate.ExercisePlan
import org.bolusai.engine.candidate.Input
import org.bolusai.engine.candidate.StepOutcome
import org.bolusai.engine.candidate.WarsawStrategy
import org.bolusai.next.application.TestCalculation
import org.bolusai.next.application.TestCalculationForm
import org.bolusai.next.ui.TestCalculationText
import org.bolusai.profile.CanonicalDecimal
import org.bolusai.profile.ConfirmationContract
import org.bolusai.profile.ConfirmationEvent
import org.bolusai.profile.ConfirmationEventKind
import org.bolusai.profile.GlucoseUnit
import org.bolusai.profile.OperationId
import org.bolusai.profile.ParameterSchedule
import org.bolusai.profile.ProfileCodec
import org.bolusai.profile.ProfileContent
import org.bolusai.profile.ProfileGateState
import org.bolusai.profile.ProfileLimit
import org.bolusai.profile.ProfileOrigin
import org.bolusai.profile.ProfileParameter
import org.bolusai.profile.ProfileRecord
import org.bolusai.profile.ProfileRecordRead
import org.bolusai.profile.ProfileTimeZone
import org.bolusai.profile.ProfileValue
import org.bolusai.profile.ProfileVersion
import org.bolusai.profile.Setting
import org.bolusai.profile.TimeZoneRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0029: fibre and Warsaw from the profile version and the meal reach the single engine. Synthetic values only:
 * ratio 10 g/U, sensitivity 40, target 110; 60 g and 190 mg/dL give meal 6 U and correction 2 U.
 */
class NutritionCalculationTest {
    private val at = 1_791_793_800_000L
    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))

    private fun profile(rules: Map<ProfileLimit, String>? = mapOf(ProfileLimit.FIBER_FACTOR to "0.5",
        ProfileLimit.FIBER_THRESHOLD to "5", ProfileLimit.WARSAW_FACTOR_SIMPLE to "0.1",
        ProfileLimit.WARSAW_FACTOR_DUAL to "0.2", ProfileLimit.WARSAW_TRIGGER to "300")): ProfileGateState {
        var content = ProfileContent(2, Setting.Declared(GlucoseUnit.MG_DL), Setting.Declared(ProfileTimeZone("Europe/Madrid")),
            listOf(ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, entered("10")),
                ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, entered("40")),
                ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, entered("110"))),
            mapOf(ProfileLimit.ROUND_STEP to entered("0.1"), ProfileLimit.MAX_BOLUS to entered("15"),
                ProfileLimit.MAX_CORRECTION to entered("5")))
        rules?.forEach { (limit, value) -> content = content.withLimit(limit, entered(value)) }
        val version = ProfileVersion(1, content, ProfileCodec.sha256(content), ProfileOrigin.MANUAL, null, 1L, "test/synthetic")
        val events = listOf(ConfirmationEvent(1, OperationId("00000000-0000-4000-8000-000000000001"),
            ConfirmationEventKind.CONFIRM, ConfirmationContract.CURRENT, 1, version.contentSha256, null, 0, 2L, "test/synthetic"))
        return ProfileGateState.of((ProfileRecord.validate(listOf(version), events) as ProfileRecordRead.Loaded).record,
            TimeZoneRules { true })
    }

    private val form = TestCalculationForm(carbs = "60", fiber = "0", fat = "0", protein = "0", glucoseMgDl = "190",
        glucoseEnteredAtEpochMs = at - 60_000, iobUnits = "0")

    private fun calculate(form: TestCalculationForm, profile: ProfileGateState = profile()) =
        TestCalculation.calculate(TestCalculation.request(at, profile, null, null, form))

    private fun total(form: TestCalculationForm, profile: ProfileGateState = profile()) =
        (calculate(form, profile).total as StepOutcome.Computed).units

    private fun mealCauses(form: TestCalculationForm, profile: ProfileGateState = profile()): List<String> {
        val result = calculate(form, profile)
        assertTrue(result.total is StepOutcome.Blocked)
        return (result.step(CandidateStep.MEAL) as StepOutcome.Blocked).causes
    }

    @Test fun fibreAboveTheThresholdIsDeductedWithTheProfileFactor() {
        // 60 g - 12 g x 0.5 = 54 g -> 5.4 U, plus 2 U of correction.
        assertEquals(7.4, total(form.copy(fiber = "12")), 1e-9)
        assertEquals(8.0, total(form.copy(fiber = "5")), 1e-9)
    }

    @Test fun warsawUsesTheSimpleFactorBelowTheTriggerAndTheDualFactorFromIt() {
        // 10 g fat + 20 g protein = 170 kcal: 1.7 FPU x 10 g x 0.1 = 1.7 g -> 0.17 U in the meal.
        val simple = calculate(form.copy(fat = "10", protein = "20"))
        assertEquals(6.17, (simple.step(CandidateStep.MEAL) as StepOutcome.Computed).units, 1e-9)
        assertEquals(0.0, (simple.step(CandidateStep.EXTENDED) as StepOutcome.Computed).units, 0.0)
        // 20 g fat + 30 g protein = 300 kcal: 3 FPU x 10 g x 0.2 = 6 g -> 0.6 U apart, added to the total.
        val dual = calculate(form.copy(fat = "20", protein = "30"))
        assertEquals(0.6, (dual.step(CandidateStep.EXTENDED) as StepOutcome.Computed).units, 1e-9)
        assertEquals(8.6, (dual.total as StepOutcome.Computed).units, 1e-9)
        assertEquals(8.3, total(form.copy(fat = "20", protein = "30", warsawStrategy = WarsawStrategy.NORMAL)), 1e-9)
        assertFalse(dual.usableForTreatment)
    }

    @Test fun aBlankOrInvalidNutrientBlocksTheMealAndIsNeverZero() {
        assertEquals(listOf("meal.fiber.missing"), mealCauses(form.copy(fiber = "")))
        assertEquals(listOf("meal.fat.missing", "meal.protein.missing"), mealCauses(form.copy(fat = "", protein = " ")))
        assertEquals(listOf("meal.protein.invalid"), mealCauses(form.copy(protein = "abc")))
    }

    @Test fun aProfileWithoutRulesComputesOnlyWhatDoesNotNeedThem() {
        val none = profile(rules = null)
        assertEquals(8.0, total(form, none), 1e-9)
        assertEquals(listOf("profile.fiber_factor.not_in_schema", "profile.fiber_threshold.not_in_schema"),
            mealCauses(form.copy(fiber = "12"), none))
        assertEquals(listOf("profile.warsaw_trigger.not_in_schema"), mealCauses(form.copy(fat = "20", protein = "30"), none))
        // Only one rule entered: the others of schema 4 stay not configured and block when read.
        val partial = profile(mapOf(ProfileLimit.FIBER_FACTOR to "0.5"))
        assertEquals(listOf("profile.fiber_threshold.not_configured"), mealCauses(form.copy(fiber = "12"), partial))
    }

    @Test fun factorsAtZeroSwitchTheRulesOffSoNutrientsAreNotNeeded() {
        val off = profile(mapOf(ProfileLimit.FIBER_FACTOR to "0", ProfileLimit.WARSAW_FACTOR_SIMPLE to "0",
            ProfileLimit.WARSAW_FACTOR_DUAL to "0"))
        assertEquals(8.0, total(form.copy(fiber = "", fat = "", protein = ""), off), 1e-9)
        assertEquals(8.0, total(form.copy(fiber = "12", fat = "40", protein = "40"), off), 1e-9)
        assertTrue("Fibra: factor de descuento: 0 × (regla desactivada)" in
            TestCalculationText.limits(TestCalculation.profile(off)).lines())
    }

    @Test fun aPlannedExerciseReducesTheDoseThroughTheEngine() {
        // ADR 0031: 8 U reduced 30 % by 60 min of moderate intensity.
        val planned = form.copy(exercisePlanned = true, exerciseMinutes = "60", exerciseIntensity = ExerciseIntensity.MODERATE)
        assertEquals(5.6, total(planned), 1e-9)
        assertEquals(Input.Provided(ExercisePlan.Planned(60, ExerciseIntensity.MODERATE)), TestCalculation.exercise(planned))
        // Minutes and intensity typed while "not planned" is selected are not used.
        assertEquals(8.0, total(planned.copy(exercisePlanned = false)), 1e-9)
        assertEquals(Input.Provided(ExercisePlan.NotPlanned), TestCalculation.exercise(form))
        assertNotEquals(calculate(form).inputFingerprintSha256, calculate(planned).inputFingerprintSha256)
        assertFalse(calculate(planned).usableForTreatment)
    }

    @Test fun aPlannedExerciseWithUnknownDataBlocksAndIsNeverReadAsNone() {
        fun exerciseCauses(form: TestCalculationForm): List<String> {
            val result = calculate(form)
            assertTrue(result.total is StepOutcome.Blocked)
            return (result.step(CandidateStep.EXERCISE) as StepOutcome.Blocked).causes
        }
        val planned = form.copy(exercisePlanned = true, exerciseMinutes = "45", exerciseIntensity = ExerciseIntensity.LOW)
        assertEquals(listOf("exercise.minutes.missing"), exerciseCauses(planned.copy(exerciseMinutes = " ")))
        assertEquals(listOf("exercise.intensity.missing"), exerciseCauses(planned.copy(exerciseIntensity = null)))
        listOf("4.5", "-10", "media hora", "45 min", "99999").forEach {
            assertEquals(it, listOf("exercise.minutes.invalid"), exerciseCauses(planned.copy(exerciseMinutes = it)))
        }
        // Zero minutes is a declared value: no reduction.
        assertEquals(8.0, total(planned.copy(exerciseMinutes = "0")), 1e-9)
    }

    @Test fun nutrientsRulesAndStrategyEnterTheFingerprint() {
        val base = calculate(form.copy(fiber = "12", fat = "20", protein = "30")).inputFingerprintSha256
        listOf(calculate(form.copy(fiber = "11", fat = "20", protein = "30")),
            calculate(form.copy(fiber = "12", fat = "20", protein = "30", warsawStrategy = WarsawStrategy.NORMAL)),
            calculate(form.copy(fiber = "12", fat = "20", protein = "30"), profile(mapOf(ProfileLimit.FIBER_FACTOR to "0.4",
                ProfileLimit.FIBER_THRESHOLD to "5", ProfileLimit.WARSAW_FACTOR_SIMPLE to "0.1",
                ProfileLimit.WARSAW_FACTOR_DUAL to "0.2", ProfileLimit.WARSAW_TRIGGER to "300"))),
        ).forEach { assertNotEquals(base, it.inputFingerprintSha256) }
    }
}
