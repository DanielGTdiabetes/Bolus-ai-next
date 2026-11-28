package org.bolusai.next

import org.bolusai.dexcomg7.ApprovedPermissionSet
import org.bolusai.dexcomg7.LatestReadingPresentation
import org.bolusai.dexcomg7.NoReadingCause
import org.bolusai.dexcomg7.WithheldCause
import org.bolusai.engine.candidate.CandidateStep
import org.bolusai.engine.candidate.GlucoseOrigin
import org.bolusai.engine.candidate.Input
import org.bolusai.engine.candidate.IobValue
import org.bolusai.engine.candidate.Quantity
import org.bolusai.engine.candidate.StepOutcome
import org.bolusai.engine.candidate.Warnings
import org.bolusai.engine.iob.CandidateIob
import org.bolusai.engine.iob.DoseHistory
import org.bolusai.engine.iob.InsulinAction
import org.bolusai.engine.iob.InsulinCurve
import org.bolusai.engine.iob.InsulinDose
import org.bolusai.engine.iob.IobOutcome
import org.bolusai.engine.iob.IobRequest
import org.bolusai.meals.DraftField
import org.bolusai.meals.MealContent
import org.bolusai.meals.MealKind
import org.bolusai.meals.MealRecord
import org.bolusai.meals.MealSelection
import org.bolusai.meals.NutritionBasis
import org.bolusai.next.application.CarbsChoice
import org.bolusai.next.application.GlucoseChoice
import org.bolusai.next.application.TestCalculation
import org.bolusai.next.application.TestCalculationForm
import org.bolusai.next.ui.TestCalculationModel
import org.bolusai.next.ui.TestCalculationText
import org.bolusai.profile.CanonicalDecimal
import org.bolusai.profile.ConfirmationContract
import org.bolusai.profile.ConfirmationEvent
import org.bolusai.profile.ConfirmationEventKind
import org.bolusai.profile.GlucoseUnit
import org.bolusai.profile.InsulinCurveCode
import org.bolusai.profile.OperationId
import org.bolusai.profile.ParameterSchedule
import org.bolusai.profile.ProfileCatalog
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
import org.bolusai.profile.VersionConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** ADR 0024 and ADR 0025: the test calculation only translates explicit inputs; synthetic profile and readings only. */
class TestCalculationTest {
    /** 2026-10-12T08:30:00Z is 10:30 in Europe/Madrid (CEST). */
    private val at = 1_791_793_800_000L

    private fun value(text: String?) = if (text == null) ProfileValue.NotConfigured else ProfileValue.Entered(CanonicalDecimal(text))

    private fun content(unit: GlucoseUnit = GlucoseUnit.MG_DL, zone: String? = "Europe/Madrid", schema: Int = 2,
                        roundStep: String? = "0.1", maxBolus: String? = "15", maxCorrection: String? = "5") = ProfileContent(
        schema, Setting.Declared(unit),
        if (zone == null) Setting.NotConfigured else Setting.Declared(ProfileTimeZone(zone)),
        listOf(
            ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, ProfileValue.Entered(CanonicalDecimal("10"))),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, ProfileValue.Entered(CanonicalDecimal("40"))),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, ProfileValue.Entered(CanonicalDecimal("110"))),
        ),
        if (schema == 1) emptyMap() else mapOf(ProfileLimit.ROUND_STEP to value(roundStep),
            ProfileLimit.MAX_BOLUS to value(maxBolus), ProfileLimit.MAX_CORRECTION to value(maxCorrection)),
    )

    private fun state(confirmed: Boolean = true, content: ProfileContent = content()): ProfileGateState {
        val version = ProfileVersion(1, content, ProfileCodec.sha256(content), ProfileOrigin.MANUAL, null, 1L, "test/synthetic")
        val events = if (confirmed) listOf(ConfirmationEvent(1, OperationId("00000000-0000-4000-8000-000000000001"),
            ConfirmationEventKind.CONFIRM, ConfirmationContract.CURRENT, 1, version.contentSha256, null, 0, 2L,
            "test/synthetic")) else emptyList()
        val record = (ProfileRecord.validate(listOf(version), events) as ProfileRecordRead.Loaded).record
        return ProfileGateState.of(record, TimeZoneRules { true })
    }

    private val form = TestCalculationForm(carbs = "60", fiber = "0", fat = "0", protein = "0", glucoseMgDl = "190",
        glucoseEnteredAtEpochMs = at - 60_000, iobUnits = "0")

    private fun reading(value: Int = 190) = LatestReadingPresentation.Reading(value, at - 120_000, 120_000,
        ApprovedPermissionSet("adr0023-i2b-2026-10-12", "c".repeat(64)), "ab12cd34", "SYNTHETIC_TREND")

    @Test fun aConfirmedProfileWithEveryInputGivesATestTotalThatIsNeverUsable() {
        val result = TestCalculation.calculate(TestCalculation.request(at, state(), null, null, form))
        assertEquals(8.0, (result.total as StepOutcome.Computed).units, 0.0)
        assertFalse(result.usableForTreatment)
        assertTrue(Warnings.TEST_ONLY in result.warnings)
    }

    @Test fun syntheticDoseHistoryFeedsOnlyTheCandidateCalculation() {
        val profile = insulinActionProfile()
        val history = syntheticDoseHistory()
        val iob = CandidateIob.calculate(IobRequest(at, Input.Provided(syntheticAction(profile)), Input.Provided(history)))
        val computed = iob.outcome as IobOutcome.Computed
        assertFalse(iob.usableForTreatment)
        assertEquals(1.0, computed.units, 0.0)
        assertEquals("synthetic-dose", computed.contributions.single().doseId)

        val result = TestCalculation.calculate(requestWithSyntheticIob(profile, computed))
        assertEquals(7.0, (result.total as StepOutcome.Computed).units, 0.0)
        assertFalse(result.usableForTreatment)
        assertTrue(Warnings.TEST_ONLY in result.warnings)

        val empty = CandidateIob.calculate(IobRequest(at, Input.Provided(syntheticAction(profile)),
            Input.Provided(history.copy(doses = emptyList())))).outcome as IobOutcome.Computed
        assertEquals(0.0, empty.units, 0.0)
        val withEmptyHistory = TestCalculation.calculate(requestWithSyntheticIob(profile, empty))
        assertEquals(8.0, (withEmptyHistory.total as StepOutcome.Computed).units, 0.0)
        assertFalse(withEmptyHistory.usableForTreatment)
    }

    @Test fun insufficientOrUnavailableSyntheticHistoryDoesNotBecomeZeroIob() {
        val profile = insulinActionProfile()
        val action = Input.Provided(syntheticAction(profile))
        val shortHistory = Input.Provided(syntheticDoseHistory().copy(coveredFromEpochMs = at - 2 * 3_600_000L))
        val missingHistory = Input.Unavailable("iob.history.unavailable")
        for ((history, cause) in listOf(shortHistory to "iob.history.insufficient_window", missingHistory to missingHistory.cause)) {
            val blocked = CandidateIob.calculate(IobRequest(at, action, history)).outcome as IobOutcome.Blocked
            assertEquals(listOf(cause), blocked.causes)
            val result = TestCalculation.calculate(requestWithSyntheticIob(profile, blocked))
            assertEquals(listOf(cause), (result.step(CandidateStep.IOB) as StepOutcome.Blocked).causes)
            assertTrue(result.total is StepOutcome.Blocked)
            assertFalse(result.usableForTreatment)
        }
    }

    @Test fun anUnconfirmedProfileCannotProduceSyntheticIob() {
        val profile = insulinActionProfile(confirmed = false)
        val iob = CandidateIob.calculate(IobRequest(at, Input.Provided(syntheticAction(profile)),
            Input.Provided(syntheticDoseHistory())))
        val blocked = iob.outcome as IobOutcome.Blocked
        assertEquals(listOf("profile.unconfirmed"), blocked.causes)
        assertTrue(TestCalculation.calculate(requestWithSyntheticIob(profile, blocked)).total is StepOutcome.Blocked)
    }

    private fun insulinActionProfile(confirmed: Boolean = true): ProfileGateState.Evaluated =
        state(confirmed, content()
            .withLimit(ProfileLimit.INSULIN_DIA, value("4"))
            .withInsulinCurve(Setting.Declared(InsulinCurveCode.LINEAR))) as ProfileGateState.Evaluated

    private fun syntheticAction(profile: ProfileGateState.Evaluated): InsulinAction {
        val version = profile.latest
        val curve = (version.content.insulinCurve as Setting.Declared).value
        val dia = (version.content.limit(ProfileLimit.INSULIN_DIA) as ProfileValue.Entered).decimal
        return InsulinAction(version.version, version.contentSha256, profile.confirmation is VersionConfirmation.Active,
            requireNotNull(InsulinCurve.fromCode(curve.code)), Quantity(dia.text),
            peakMinutes = Input.Unavailable("profile.insulin_peak.not_configured"))
    }

    private fun syntheticDoseHistory() = DoseHistory(
        sourceReference = "synthetic-test-history",
        coveredFromEpochMs = at - 4 * 3_600_000L,
        coveredUntilEpochMs = at,
        maxExtendedMinutes = 0,
        doses = listOf(InsulinDose("synthetic-dose", at - 2 * 3_600_000L, Quantity("2"), extendedMinutes = 0)),
    )

    // This bridge exists only in the test source set. IobValue currently tags every provided value as manual.
    // The fixture is exactly representable by Quantity. A future actual adapter must preserve IOB provenance.
    private fun requestWithSyntheticIob(profile: ProfileGateState, outcome: IobOutcome) =
        TestCalculation.request(at, profile, null, null, form.copy(iobUnits = "")).copy(iob = when (outcome) {
            is IobOutcome.Computed -> Input.Provided(IobValue(Quantity(BigDecimal.valueOf(outcome.units)
                .stripTrailingZeros().toPlainString())))
            is IobOutcome.Blocked -> Input.Unavailable(outcome.causes.single())
        })

    @Test fun theLocalMinuteComesFromTheProfileZone() {
        assertEquals(Input.Provided(630), TestCalculation.localMinute(state(), at))
        assertEquals(Input.Unavailable("profile.time_zone.not_configured"),
            TestCalculation.localMinute(state(content = content(zone = null)), at))
        assertEquals(Input.Unavailable("profile.time_zone.unrecognized"),
            TestCalculation.localMinute(state(content = content(zone = "Mars/Olympus")), at))
    }

    @Test fun unconfirmedMissingOrPendingProfilesBlock() {
        val unconfirmed = TestCalculation.calculate(TestCalculation.request(at, state(confirmed = false), null, null, form))
        assertEquals(listOf("profile.unconfirmed"), (unconfirmed.step(CandidateStep.MEAL) as StepOutcome.Blocked).causes)
        assertEquals(Input.Unavailable("profile.read.pending"), TestCalculation.profile(null))
        assertEquals(Input.Unavailable("profile.read.pending"), TestCalculation.profile(ProfileGateState.ReadPending))
    }

    @Test fun blankFieldsAreAbsentNeverZeroAndInvalidTextIsNamed() {
        val request = TestCalculation.request(at, state(), null, null, TestCalculationForm(carbs = "1.234,5"))
        assertEquals(Input.Unavailable("meal.carbs.invalid"), request.carbs)
        assertEquals(Input.Unavailable("glucose.manual.missing"), request.glucose)
        assertEquals(Input.Unavailable("iob.unknown"), request.iob)
    }

    @Test fun limitsComeFromTheProfileVersionWithoutBeingTypedAgain() {
        val snapshot = (TestCalculation.request(at, state(), null, null, form).profile as Input.Provided).value
        assertEquals("0.1", (snapshot.roundStep as Input.Provided).value.text)
        assertEquals("15", (snapshot.maxBolus as Input.Provided).value.text)
        assertEquals("5", (snapshot.maxCorrection as Input.Provided).value.text)
        // Two calculations with the same form reuse the stored limits.
        val again = TestCalculation.calculate(TestCalculation.request(at + 1, state(), null, null, form))
        assertEquals(8.0, (again.total as StepOutcome.Computed).units, 0.0)
    }

    @Test fun aSchemaOneVersionKeepsItsLimitsUnknownAndBlocksOnlyWhatNeedsThem() {
        val old = state(content = content(schema = 1))
        val snapshot = (TestCalculation.profile(old) as Input.Provided).value
        assertEquals(Input.Unavailable("profile.round_step.not_in_schema"), snapshot.roundStep)
        assertEquals(Input.Unavailable("profile.max_bolus.not_in_schema"), snapshot.maxBolus)
        assertEquals(Input.Unavailable("profile.max_correction.not_in_schema"), snapshot.maxCorrection)
        val result = TestCalculation.calculate(TestCalculation.request(at, old, null, null, form))
        assertEquals(6.0, (result.step(CandidateStep.MEAL) as StepOutcome.Computed).units, 0.0)
        assertEquals(2.0, (result.step(CandidateStep.CORRECTION) as StepOutcome.Computed).units, 0.0)
        assertEquals(listOf("profile.max_correction.not_in_schema"),
            (result.step(CandidateStep.CORRECTION_LIMIT) as StepOutcome.Blocked).causes)
        assertTrue(result.total is StepOutcome.Blocked)
    }

    @Test fun anUnconfiguredLimitBlocksOnlyItsOwnStep() {
        val result = TestCalculation.calculate(TestCalculation.request(at, state(content = content(maxBolus = null)),
            null, null, form))
        assertEquals(8.0, (result.step(CandidateStep.ROUNDING) as StepOutcome.Computed).units, 0.0)
        assertEquals(listOf("profile.max_bolus.not_configured"),
            (result.step(CandidateStep.MAX_BOLUS) as StepOutcome.Blocked).causes)
        val zeroStep = TestCalculation.calculate(TestCalculation.request(at, state(content = content(roundStep = "0")),
            null, null, form))
        assertEquals(listOf("round_step.not_positive"), (zeroStep.step(CandidateStep.ROUNDING) as StepOutcome.Blocked).causes)
    }

    @Test fun limitsAreReadOnlyHereAndShowTheirOrigin() {
        fun firstLines(text: String) = text.lines().take(4).joinToString("\n")
        val confirmed = TestCalculationText.limits(TestCalculation.profile(state()))
        assertEquals("Perfil v1 confirmado\nPaso de redondeo: 0,1 U\nBolo máximo: 15 U\nCorrección máxima: 5 U",
            firstLines(confirmed))
        // ADR 0029: a version without nutrition rules shows them as unknown, never as off.
        assertEquals(listOf("Fibra: factor de descuento: desconocido (profile.fiber_factor.not_in_schema)",
            "Fibra: umbral: desconocido (profile.fiber_threshold.not_in_schema)",
            "Warsaw: factor simple: desconocido (profile.warsaw_factor_simple.not_in_schema)",
            "Warsaw: factor dual: desconocido (profile.warsaw_factor_dual.not_in_schema)",
            "Warsaw: umbral: desconocido (profile.warsaw_trigger.not_in_schema)"), confirmed.lines().drop(4))
        assertEquals("Perfil v1 sin confirmar (profile.unconfirmed)\n" +
            "Paso de redondeo: desconocido (profile.round_step.not_in_schema)\n" +
            "Bolo máximo: desconocido (profile.max_bolus.not_in_schema)\n" +
            "Corrección máxima: desconocido (profile.max_correction.not_in_schema)",
            firstLines(TestCalculationText.limits(TestCalculation.profile(state(confirmed = false, content = content(schema = 1))))))
        assertEquals("Límites del perfil no disponibles (profile.missing)",
            TestCalculationText.limits(Input.Unavailable("profile.missing")))
    }

    @Test fun dexcomGlucoseIsUsedOnlyWhenTheNumberIsShownAndKeepsItsLimitations() {
        val used = TestCalculation.dexcomGlucose(reading()) as Input.Provided
        assertEquals("190", used.value.value.text)
        assertEquals(GlucoseOrigin.DEXCOM_PERMISSION_SET, used.value.origin)
        assertFalse(used.value.measurementTimeVerified)
        assertEquals(Input.Unavailable("glucose.dexcom_presentation.outside_producer_interval"), TestCalculation.dexcomGlucose(
            LatestReadingPresentation.Withheld(WithheldCause.OUTSIDE_PRODUCER_INTERVAL, at, reading().provenance)))
        assertEquals(Input.Unavailable("glucose.dexcom_presentation.receiver_disabled"), TestCalculation.dexcomGlucose(
            LatestReadingPresentation.NoReading(NoReadingCause.RECEIVER_DISABLED, null)))
        val result = TestCalculation.calculate(TestCalculation.request(at, state(), null, reading(),
            form.copy(glucoseChoice = GlucoseChoice.DEXCOM)))
        assertTrue(Warnings.DEXCOM_NOT_ATTRIBUTED in result.warnings)
    }

    private fun record(revision: Long, carbs: DraftField, basis: NutritionBasis = NutritionBasis.TOTAL_GRAMS) =
        MealRecord("meal-1", revision, MealKind.DRAFT, MealContent(carbs = carbs, basis = basis), null)

    @Test fun mealCarbsComeOnlyFromACurrentTotalGramsRevision() {
        val current = MealSelection.Reviewed(record(2, DraftField.Entered("45")), record(2, DraftField.Entered("45")))
        val carbs = TestCalculation.mealCarbs(current) as Input.Provided
        assertEquals("45", carbs.value.grams.text)
        assertEquals(Input.Unavailable("meal.selection.obsolete"), TestCalculation.mealCarbs(
            MealSelection.Reviewed(record(1, DraftField.Entered("45")), record(2, DraftField.Entered("50")))))
        assertEquals(Input.Unavailable("meal.basis.not_total_grams"), TestCalculation.mealCarbs(MealSelection.Reviewed(
            record(2, DraftField.Entered("45"), NutritionBasis.UNSPECIFIED), record(2, DraftField.Entered("45"), NutritionBasis.UNSPECIFIED))))
        assertEquals(Input.Unavailable("meal.carbs.missing"),
            TestCalculation.mealCarbs(MealSelection.Reviewed(record(2, DraftField.Missing), record(2, DraftField.Missing))))
        assertEquals(Input.Unavailable("meal.selection.missing"), TestCalculation.mealCarbs(MealSelection.Missing))
        val request = TestCalculation.request(at, state(), current, null, form.copy(carbsChoice = CarbsChoice.MEAL_SELECTION))
        assertEquals("45", (request.carbs as Input.Provided).value.grams.text)
    }

    @Test fun mealNutrientsComeFromTheSameRevisionAndAMissingOneIsNeverZero() {
        fun full(fiber: DraftField) = MealRecord("meal-1", 2, MealKind.DRAFT, MealContent(carbs = DraftField.Entered("60"),
            fat = DraftField.Entered("20"), protein = DraftField.Entered("30"), fiber = fiber,
            basis = NutritionBasis.TOTAL_GRAMS), null)
        val complete = MealSelection.Reviewed(full(DraftField.Entered("12")), full(DraftField.Entered("12")))
        val nutrition = TestCalculation.mealNutrition(complete)
        assertEquals(listOf("12", "20", "30"), listOf(nutrition.fiber, nutrition.fat, nutrition.protein)
            .map { (it as Input.Provided).value.text })
        val missing = MealSelection.Reviewed(full(DraftField.Missing), full(DraftField.Missing))
        assertEquals(Input.Unavailable("meal.fiber.missing"), TestCalculation.mealNutrition(missing).fiber)
        assertEquals(Input.Unavailable("meal.fat.invalid"), TestCalculation.mealNutrition(MealSelection.Reviewed(
            full(DraftField.Entered("1")).let { it.copy(content = it.content.copy(fat = DraftField.Entered("mucha"))) },
            full(DraftField.Entered("1")).let { it.copy(content = it.content.copy(fat = DraftField.Entered("mucha"))) })).fat)
        assertEquals(Input.Unavailable("meal.selection.missing"), TestCalculation.mealNutrition(MealSelection.Missing).protein)
        assertEquals(Input.Unavailable("meal.selection.obsolete"), TestCalculation.mealNutrition(
            MealSelection.Reviewed(record(1, DraftField.Entered("45")), record(2, DraftField.Entered("50")))).fiber)
        val mealForm = form.copy(carbsChoice = CarbsChoice.MEAL_SELECTION)
        assertEquals(nutrition, TestCalculation.request(at, state(), complete, null, mealForm).nutrition)
        // The typed nutrients of the manual form are not used with a meal selection.
        assertEquals(nutrition, TestCalculation.request(at, state(), complete, null, mealForm.copy(fiber = "99")).nutrition)
        assertEquals(nutrition, TestCalculation.dependencies(state(), complete, null, mealForm).mealNutrition)
        assertNull(TestCalculation.dependencies(state(), complete, null, form).mealNutrition)
    }

    @Test fun manualGlucoseKeepsItsEntryInstantNotTheCalculationTime() {
        val first = TestCalculation.request(at, state(), null, null, form)
        val later = TestCalculation.request(at + 600_000, state(), null, null, form)
        assertEquals(at - 60_000, (first.glucose as Input.Provided).value.recordedAtEpochMs)
        assertEquals(first.glucose, later.glucose)
        assertEquals(Input.Unavailable("glucose.manual.entry_time_unknown"),
            TestCalculation.request(at, state(), null, null, form.copy(glucoseEnteredAtEpochMs = null)).glucose)
    }

    @Test fun dexcomProvenanceAndTransmitterEnterTheFingerprint() {
        val dexcomForm = form.copy(glucoseChoice = GlucoseChoice.DEXCOM)
        val base = TestCalculation.calculate(TestCalculation.request(at, state(), null, reading(), dexcomForm))
        val otherSet = reading().copy(provenance = ApprovedPermissionSet("adr0023-i2b-2026-10-12", "d".repeat(64)))
        val otherTx = reading().copy(transmitterShort = "ffff0000")
        listOf(otherSet, otherTx).forEach {
            assertTrue(base.inputFingerprintSha256 != TestCalculation.calculate(
                TestCalculation.request(at, state(), null, it, dexcomForm)).inputFingerprintSha256)
        }
    }

    @Test fun aNewReadingMealOrProfileDiscardsTheResultButAnUnusedSourceDoesNot() {
        val dexcomForm = form.copy(glucoseChoice = GlucoseChoice.DEXCOM)
        val model = TestCalculationModel()
        model.update(dexcomForm)
        model.store(TestCalculation.calculate(TestCalculation.request(at, state(), null, reading(), dexcomForm)), at,
            TestCalculation.dependencies(state(), null, reading(), dexcomForm))
        // Reloading the same reading only changes the elapsed time: the result stays.
        model.invalidateIfChanged(TestCalculation.dependencies(state(), null, reading().copy(elapsedSinceReceptionMs = 1), dexcomForm))
        assertTrue(model.result != null)
        // The meal selection is not used by this form.
        model.invalidateIfChanged(TestCalculation.dependencies(state(), MealSelection.Missing, reading(), dexcomForm))
        assertTrue(model.result != null)
        model.invalidateIfChanged(TestCalculation.dependencies(state(), null, reading(value = 191), dexcomForm))
        assertNull(model.result)
        assertTrue(model.invalidated)
        model.store(TestCalculation.calculate(TestCalculation.request(at, state(), null, reading(), dexcomForm)), at,
            TestCalculation.dependencies(state(), null, reading(), dexcomForm))
        model.invalidateIfChanged(TestCalculation.dependencies(state(confirmed = false), null, reading(), dexcomForm))
        assertNull(model.result)
    }

    @Test fun anotherLimitInTheProfileDiscardsTheResultAndChangesTheFingerprint() {
        val model = TestCalculationModel()
        model.update(form)
        val base = TestCalculation.calculate(TestCalculation.request(at, state(), null, null, form))
        model.store(base, at, TestCalculation.dependencies(state(), null, null, form))
        model.invalidateIfChanged(TestCalculation.dependencies(state(), null, null, form))
        assertTrue(model.result != null)
        listOf(content(roundStep = "0.5"), content(maxBolus = "14"), content(maxCorrection = null), content(schema = 1))
            .forEach { changed ->
                val other = state(content = changed)
                assertTrue(base.inputFingerprintSha256 != TestCalculation.calculate(
                    TestCalculation.request(at, other, null, null, form)).inputFingerprintSha256)
                model.store(base, at, TestCalculation.dependencies(state(), null, null, form))
                model.invalidateIfChanged(TestCalculation.dependencies(other, null, null, form))
                assertNull(model.result)
                assertTrue(model.invalidated)
            }
    }

    @Test fun changingTheFormDiscardsTheResult() {
        val model = TestCalculationModel()
        model.update(form)
        model.store(TestCalculation.calculate(TestCalculation.request(at, state(), null, null, form)), at,
            TestCalculation.dependencies(state(), null, null, form))
        model.update(form)
        assertTrue(model.result != null)
        model.update(form.copy(iobUnits = "1"))
        assertNull(model.result)
        assertTrue(model.invalidated)
    }

    @Test fun theScreenListsRealWarningsAndNotThePendingPolicyOnes() {
        // Owner decision 2026-11-28 (ADR 0024): the engine keeps every warning; the screen omits the policy ones.
        val result = TestCalculation.calculate(TestCalculation.request(at, state(), null, null, form))
        assertTrue(Warnings.TEST_ONLY in result.warnings)
        val shown = TestCalculationText.shownWarnings(result.warnings + Warnings.BELOW_HYPO_STOP + "unknown.code")
        listOf(Warnings.TEST_ONLY, Warnings.RULES_NOT_APPROVED, Warnings.FRESHNESS_NOT_APPROVED,
            Warnings.MEASUREMENT_TIME_UNVERIFIED, Warnings.DEXCOM_NOT_ATTRIBUTED).forEach { assertFalse(it, it in shown) }
        assertTrue(Warnings.MANUAL_IOB in shown)
        assertTrue(Warnings.BELOW_HYPO_STOP in shown)
        assertTrue("unknown.code" in shown)
    }

    @Test fun displayFormattingIsNotAClinicalRounding() {
        assertEquals("2,2", TestCalculationText.units(2.2000000000000002))
        assertEquals("3,364", TestCalculationText.units(3.3636363636363638))
        assertEquals("0", TestCalculationText.units(0.0))
    }
}
