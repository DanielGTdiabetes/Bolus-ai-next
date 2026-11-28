package org.bolusai.next

import org.bolusai.engine.candidate.CandidateStep
import org.bolusai.engine.candidate.Input
import org.bolusai.engine.candidate.IobValue
import org.bolusai.engine.candidate.StepOutcome
import org.bolusai.engine.candidate.Warnings
import org.bolusai.engine.iob.IobOutcome
import org.bolusai.insulin.AppendOutcome
import org.bolusai.insulin.DoseHistoryLog
import org.bolusai.insulin.DoseOrigin
import org.bolusai.insulin.InMemoryDoseLedger
import org.bolusai.insulin.LedgerRead
import org.bolusai.next.application.CalculationAudit
import org.bolusai.next.application.IobChoice
import org.bolusai.next.application.TestCalculation
import org.bolusai.next.application.TestCalculationForm
import org.bolusai.next.ui.DoseHistoryText
import org.bolusai.next.ui.TestCalculationText
import org.bolusai.profile.CanonicalDecimal
import org.bolusai.profile.ConfirmationContract
import org.bolusai.profile.ConfirmationEvent
import org.bolusai.profile.ConfirmationEventKind
import org.bolusai.profile.GlucoseUnit
import org.bolusai.profile.InsulinCurveCode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0028: the test calculation takes the IOB from the local dose ledger through the single engine. Synthetic
 * profile and doses only. Ratio 10 g/U, sensitivity 40, target 110, linear curve with DIA 4 h.
 */
class DoseHistoryCalculationTest {
    private val hour = 3_600_000L
    /** 2026-10-12T08:30:00Z. */
    private val at = 1_791_793_800_000L
    private var now = at - 5 * hour
    private val ledger = InMemoryDoseLedger()
    private val log = DoseHistoryLog(ledger, { now }, "test/synthetic")

    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))

    private fun profile(confirmed: Boolean = true, action: Boolean = true): ProfileGateState {
        var content = ProfileContent(2, Setting.Declared(GlucoseUnit.MG_DL), Setting.Declared(ProfileTimeZone("Europe/Madrid")),
            listOf(ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, entered("10")),
                ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, entered("40")),
                ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, entered("110"))),
            mapOf(ProfileLimit.ROUND_STEP to entered("0.1"), ProfileLimit.MAX_BOLUS to entered("15"),
                ProfileLimit.MAX_CORRECTION to entered("5")))
        if (action) {
            content = content.withLimit(ProfileLimit.INSULIN_DIA, entered("4"))
                .withInsulinCurve(Setting.Declared(InsulinCurveCode.LINEAR))
        }
        val version = ProfileVersion(1, content, ProfileCodec.sha256(content), ProfileOrigin.MANUAL, null, 1L, "test/synthetic")
        val events = if (confirmed) listOf(ConfirmationEvent(1, OperationId("00000000-0000-4000-8000-000000000001"),
            ConfirmationEventKind.CONFIRM, ConfirmationContract.CURRENT, 1, version.contentSha256, null, 0, 2L,
            "test/synthetic")) else emptyList()
        return ProfileGateState.of((ProfileRecord.validate(listOf(version), events) as ProfileRecordRead.Loaded).record,
            TimeZoneRules { true })
    }

    /** 60 g and 190 mg/dL: meal 6 U, correction 2 U, 8 U without IOB. */
    private val form = TestCalculationForm(carbs = "60", fiber = "0", fat = "0", protein = "0", glucoseMgDl = "190",
        glucoseEnteredAtEpochMs = at - 60_000, iobChoice = IobChoice.DOSE_HISTORY)

    private fun calculate(profile: ProfileGateState = profile(), doses: LedgerRead? = log.read()) =
        TestCalculation.calculate(TestCalculation.request(at, profile, null, null, form, doses))

    private fun total(profile: ProfileGateState = profile(), doses: LedgerRead? = log.read()) =
        (calculate(profile, doses).total as StepOutcome.Computed).units

    private fun blockedBy(profile: ProfileGateState = profile(), doses: LedgerRead? = log.read()): List<String> {
        val result = calculate(profile, doses)
        assertTrue(result.total is StepOutcome.Blocked)
        return (result.steps.first { it.step == CandidateStep.IOB }.outcome as StepOutcome.Blocked).causes
    }

    @Test fun aLedgerCoveringTheWholeActionWithNoDosesGivesZeroIob() {
        log.startTracking("t1")
        assertEquals(8.0, total(), 0.0)
        val iob = TestCalculation.iob(at, profile(), log.read())
        assertEquals(0.0, (iob.outcome as IobOutcome.Computed).units, 0.0)
        assertFalse(iob.usableForTreatment)
    }

    @Test fun aNotedDoseReducesThePositiveCorrectionThroughTheEngine() {
        log.startTracking("t1")
        now = at - 2 * hour
        assertEquals(AppendOutcome.Appended, log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null))
        // Linear curve, DIA 4 h, 2 h elapsed: 1 U left of 2 U. Correction 2 U - 1 U = 1 U; total 7 U.
        val result = calculate()
        assertEquals(7.0, (result.total as StepOutcome.Computed).units, 0.0)
        assertTrue(Warnings.CALCULATED_IOB in result.warnings)
        assertFalse(Warnings.MANUAL_IOB in result.warnings)
        assertTrue(Warnings.TEST_ONLY in result.warnings)
        assertFalse(result.usableForTreatment)
    }

    @Test fun theBolusFingerprintCarriesTheIobProvenance() {
        log.startTracking("t1")
        now = at - 2 * hour
        log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
        val request = TestCalculation.request(at, profile(), null, null, form, log.read())
        val iob = TestCalculation.iob(at, profile(), log.read())
        val value = (request.iob as Input.Provided).value
        assertEquals(IobValue.CALCULATED, value.origin)
        assertEquals(iob.inputFingerprintSha256, value.reference)
        val manual = TestCalculation.request(at, profile(), null, null, form.copy(iobChoice = IobChoice.MANUAL, iobUnits = "1"),
            log.read())
        assertEquals(TestCalculation.calculate(request).total, TestCalculation.calculate(manual).total)
        assertNotEquals(TestCalculation.calculate(request).inputFingerprintSha256,
            TestCalculation.calculate(manual).inputFingerprintSha256)
    }

    @Test fun aVoidedDoseStopsCounting() {
        log.startTracking("t1")
        now = at - 2 * hour
        log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
        now = at - hour
        log.voidDose("v1", "d1")
        assertEquals(8.0, total(), 0.0)
    }

    @Test fun anUnknownHistoryNeverBecomesZeroIob() {
        assertEquals(listOf("iob.history.read_pending"), blockedBy(doses = null))
        assertEquals(listOf("iob.history.unreadable"), blockedBy(doses = LedgerRead.Failed("dose_ledger.read_failed")))
        assertEquals(listOf("iob.history.tracking_not_started"), blockedBy())
    }

    @Test fun aLedgerStartedInsideTheActionWindowBlocks() {
        now = at - 3 * hour
        log.startTracking("t1")
        assertEquals(listOf("iob.history.insufficient_window"), blockedBy())
        // Exactly the DIA is enough.
        val enough = InMemoryDoseLedger()
        DoseHistoryLog(enough, { at - 4 * hour }, "test/synthetic").startTracking("t1")
        assertEquals(8.0, total(doses = enough.read()), 0.0)
    }

    @Test fun aProfileWithoutConfirmedInsulinActionBlocks() {
        log.startTracking("t1")
        assertEquals(listOf("profile.insulin_curve.not_in_schema"), blockedBy(profile(action = false)))
        assertTrue("profile.unconfirmed" in
            (TestCalculation.iob(at, profile(confirmed = false), log.read()).outcome as IobOutcome.Blocked).causes)
    }

    @Test fun aDoseAfterTheCalculationInstantBlocks() {
        log.startTracking("t1")
        now = at + 1
        log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
        assertEquals(listOf("iob.history.future_dose"), blockedBy())
    }

    @Test fun theManualChoiceIgnoresTheLedgerAndKeepsItsBehaviour() {
        log.startTracking("t1")
        now = at - 2 * hour
        log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
        val manual = form.copy(iobChoice = IobChoice.MANUAL, iobUnits = "0")
        val result = TestCalculation.calculate(TestCalculation.request(at, profile(), null, null, manual, log.read()))
        assertEquals(8.0, (result.total as StepOutcome.Computed).units, 0.0)
        assertTrue(Warnings.MANUAL_IOB in result.warnings)
        assertNull(TestCalculation.dependencies(profile(), null, null, manual, log.read()).doses)
    }

    @Test fun aNewDoseChangesTheDependenciesOfAStoredResult() {
        log.startTracking("t1")
        val before = TestCalculation.dependencies(profile(), null, null, form, log.read())
        now = at - hour
        log.noteDose("d1", "1", DoseOrigin.USER_NOTE, null)
        assertNotEquals(before, TestCalculation.dependencies(profile(), null, null, form, log.read()))
    }

    @Test fun onlyAPositiveRepresentableTotalCanBeNotedAsADose() {
        assertEquals("7", DoseHistoryText.totalUnits(7.0))
        assertEquals("3.55", DoseHistoryText.totalUnits(3.55))
        assertNull(DoseHistoryText.totalUnits(0.0))
        // Binary noise of the engine rounding (0.1 * 3, 0.05 * 7) is not a real digit.
        assertEquals("0.3", DoseHistoryText.totalUnits(0.1 * 3))
        assertEquals("0.35", DoseHistoryText.totalUnits(0.05 * 7))
        assertEquals("14.95", DoseHistoryText.totalUnits(299 * 0.05))
        assertNull(DoseHistoryText.totalUnits(1.0005))
        assertNull(DoseHistoryText.totalUnits(0.0000000001))
        assertNull(DoseHistoryText.totalUnits(1.23456))
        assertNull(DoseHistoryText.totalUnits(Double.NaN))
    }

    @Test fun theAuditRecordOfANotedTotalSurvivesInTheLedgerAndMatchesItsFingerprint() {
        log.startTracking("t1")
        now = at - 2 * hour
        log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
        val request = TestCalculation.request(at, profile(), null, null, form, log.read())
        val result = TestCalculation.calculate(request)
        val iob = TestCalculation.iob(at, profile(), log.read())
        val audit = CalculationAudit.record(request, result, iob)
        assertEquals(result.inputFingerprintSha256, audit.inputSha256)
        assertEquals(audit.inputSha256, java.security.MessageDigest.getInstance("SHA-256")
            .digest(audit.requestText.toByteArray()).joinToString("") { "%02x".format(it) })
        for (line in listOf("usable_for_treatment=false", "validation=pending", "input_sha256=${audit.inputSha256}",
            "total=7.0", "step.meal=6.0", "step.iob.fact.iob_u=1.0", "warning=iob.calculated_candidate_unverified",
            "iob.input_sha256=${iob.inputFingerprintSha256}", "iob.units=1.0", "iob.dose.d1=units:2.0;elapsed_min:120.0;remaining:1.0")) {
            assertTrue(line, line in audit.resultText.lines())
        }
        assertTrue("carbs=60:manual_entry" in audit.requestText.lines())
        now = at
        assertEquals(AppendOutcome.Appended, log.noteDose("d2", "7", DoseOrigin.TEST_CALCULATION, audit))
        assertEquals(audit, (log.read() as LedgerRead.Available).state.doses.last().calculation)
    }

    @Test fun anAuditRecordNeverCombinesAResultWithAnotherRequest() {
        log.startTracking("t1")
        val request = TestCalculation.request(at, profile(), null, null, form, log.read())
        val other = TestCalculation.calculate(TestCalculation.request(at + 1, profile(), null, null, form, log.read()))
        try {
            CalculationAudit.record(request, other, null)
            org.junit.Assert.fail("a result must match its request")
        } catch (expected: IllegalStateException) {
            assertEquals("calculation_audit.request_does_not_match_result", expected.message)
        }
    }

    @Test fun theIobBreakdownNeverShowsABlockedIobAsZero() {
        val blocked = TestCalculationText.iob(TestCalculation.iob(at, profile(), log.read()))
        assertEquals("IOB del historial: bloqueado (iob.history.tracking_not_started)", blocked.first())
        log.startTracking("t1")
        now = at - 2 * hour
        log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
        val lines = TestCalculationText.iob(TestCalculation.iob(at, profile(), log.read()))
        assertTrue(lines.first().startsWith("IOB del historial: 1 U · 1 dosis"))
        assertEquals("   2 U hace 120 min → quedan 1 U", lines[1])
        // Owner decision 2026-11-28: the validation state stays in the result and the audit, not on the screen.
        assertFalse("validación" in lines.last())
    }
}
