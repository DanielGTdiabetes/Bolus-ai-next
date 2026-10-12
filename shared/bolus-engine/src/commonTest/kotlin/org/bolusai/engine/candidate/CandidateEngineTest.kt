package org.bolusai.engine.candidate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private fun q(text: String) = Quantity(text)

private fun allDay(value: String?) = listOf(SegmentValue(0, 1440,
    if (value == null) Input.Unavailable("profile.value.not_configured") else Input.Provided(q(value))))

internal fun profile(
    carbRatio: String? = "10",
    sensitivity: String? = "40",
    target: String? = "110",
    unit: GlucoseUnitCode? = GlucoseUnitCode.MG_DL,
    confirmed: Boolean = true,
    carbRatioSegments: List<SegmentValue>? = null,
) = ProfileSnapshot(
    version = 3,
    fingerprintSha256 = FINGERPRINT,
    dataConfirmed = confirmed,
    glucoseUnit = if (unit == null) Input.Unavailable("profile.unit.not_configured") else Input.Provided(unit),
    timeZoneId = Input.Provided("Europe/Madrid"),
    carbRatio = carbRatioSegments ?: allDay(carbRatio),
    sensitivity = allDay(sensitivity),
    target = allDay(target),
)

internal fun request(
    profile: Input<ProfileSnapshot> = Input.Provided(profile()),
    minute: Input<Int> = Input.Provided(600),
    carbs: String? = "60",
    glucose: Input<GlucoseValue> = Input.Provided(GlucoseValue(q("190"), GlucoseUnitCode.MG_DL,
        GlucoseOrigin.MANUAL_ENTRY, 1_000L, measurementTimeVerified = false, sourceReference = "manual")),
    iob: String? = "0",
    roundStep: String? = "0.1",
    maxBolus: String? = "15",
    maxCorrection: String? = "5",
) = CandidateRequest(
    calculationAtEpochMs = 2_000L,
    localMinuteOfDay = minute,
    profile = profile,
    carbs = if (carbs == null) Input.Unavailable("meal.carbs.missing") else
        Input.Provided(CarbsValue(q(carbs), CarbsOrigin.MANUAL_ENTRY)),
    glucose = glucose,
    iob = if (iob == null) Input.Unavailable("iob.unknown") else Input.Provided(IobValue(q(iob))),
    roundStep = if (roundStep == null) Input.Unavailable("limits.round_step.missing") else Input.Provided(q(roundStep)),
    maxBolus = if (maxBolus == null) Input.Unavailable("limits.max_bolus.missing") else Input.Provided(q(maxBolus)),
    maxCorrection = if (maxCorrection == null) Input.Unavailable("limits.max_correction.missing") else
        Input.Provided(q(maxCorrection)),
)

private fun units(outcome: StepOutcome): Double = (outcome as StepOutcome.Computed).units
private fun causes(outcome: StepOutcome): List<String> = (outcome as StepOutcome.Blocked).causes

class CandidateEngineTest {
    @Test
    fun matchesEveryVectorCapturedFromLegacyExactly() {
        assertEquals(CandidateRuleSet.LEGACY_SHA, LEGACY_VECTORS_SHA)
        assertTrue(LEGACY_VECTORS.size >= 10)
        for (vector in LEGACY_VECTORS) {
            val result = CandidateEngine.calculate(request(
                profile = Input.Provided(profile(vector.carbRatio, vector.sensitivity, vector.target)),
                carbs = vector.carbs,
                glucose = Input.Provided(GlucoseValue(q(vector.glucose), GlucoseUnitCode.MG_DL,
                    GlucoseOrigin.MANUAL_ENTRY, 0L, false, "manual")),
                iob = vector.iob, roundStep = vector.roundStep, maxBolus = vector.maxBolus,
                maxCorrection = vector.maxCorrection,
            ))
            assertEquals(vector.mealU, units(result.step(CandidateStep.MEAL)), vector.name)
            assertEquals(vector.correctionU, units(result.step(CandidateStep.CORRECTION_LIMIT)), vector.name)
            val iobFacts = (result.step(CandidateStep.IOB) as StepOutcome.Computed).facts
            assertEquals(vector.iobAppliedU, iobFacts.first { it.key == "iob_applied_to_correction_u" }.value, vector.name)
            assertEquals(vector.totalU, units(result.total), vector.name)
        }
    }

    @Test
    fun everyResultIsATestResultThatCannotBeUsedForTreatment() {
        val result = CandidateEngine.calculate(request())
        assertFalse(result.usableForTreatment)
        assertEquals("pending", result.ruleValidation)
        assertEquals(CandidateRuleSet.ID, result.ruleSetId)
        assertTrue(Warnings.TEST_ONLY in result.warnings)
        assertTrue(Warnings.RULES_NOT_APPROVED in result.warnings)
        assertTrue(Warnings.FRESHNESS_NOT_APPROVED in result.warnings)
        assertTrue(Warnings.MEASUREMENT_TIME_UNVERIFIED in result.warnings)
        assertTrue(Warnings.MANUAL_IOB in result.warnings)
        assertEquals(CandidateRuleSet.NOT_APPLIED, result.notApplied)
    }

    @Test
    fun missingGlucoseBlocksCorrectionAndTotalButKeepsTheMealComponent() {
        val result = CandidateEngine.calculate(request(glucose = Input.Unavailable("glucose.missing")))
        assertEquals(6.0, units(result.step(CandidateStep.MEAL)))
        assertEquals(listOf("glucose.missing"), causes(result.step(CandidateStep.CORRECTION)))
        assertEquals(listOf("depends_on.correction"), causes(result.step(CandidateStep.CORRECTION_LIMIT)))
        assertTrue(result.total is StepOutcome.Blocked)
        assertFalse(Warnings.FRESHNESS_NOT_APPROVED in result.warnings)
    }

    @Test
    fun unknownIobIsNeverZero() {
        val result = CandidateEngine.calculate(request(iob = null))
        assertEquals(2.0, units(result.step(CandidateStep.CORRECTION_LIMIT)))
        assertEquals(listOf("iob.unknown"), causes(result.step(CandidateStep.IOB)))
        assertEquals(listOf("depends_on.iob"), causes(result.step(CandidateStep.ROUNDING)))
        assertTrue(result.total is StepOutcome.Blocked)
    }

    @Test
    fun missingCarbsBlockOnlyTheMealAndWhatDependsOnIt() {
        val result = CandidateEngine.calculate(request(carbs = null))
        assertEquals(listOf("meal.carbs.missing"), causes(result.step(CandidateStep.MEAL)))
        assertEquals(2.0, units(result.step(CandidateStep.CORRECTION)))
        assertEquals(listOf("depends_on.meal"), causes(result.step(CandidateStep.IOB)))
    }

    @Test
    fun zeroCarbsNeedNoCarbRatio() {
        val result = CandidateEngine.calculate(request(
            profile = Input.Provided(profile(carbRatio = null)), carbs = "0"))
        assertEquals(0.0, units(result.step(CandidateStep.MEAL)))
        assertEquals(2.0, units(result.total))
    }

    @Test
    fun eachMissingLimitBlocksItsOwnStep() {
        assertEquals(listOf("limits.max_correction.missing"),
            causes(CandidateEngine.calculate(request(maxCorrection = null)).step(CandidateStep.CORRECTION_LIMIT)))
        val noStep = CandidateEngine.calculate(request(roundStep = null))
        assertEquals(8.0, units(noStep.step(CandidateStep.IOB)))
        assertEquals(listOf("limits.round_step.missing"), causes(noStep.step(CandidateStep.ROUNDING)))
        assertEquals(listOf("limits.max_bolus.missing"),
            causes(CandidateEngine.calculate(request(maxBolus = null)).step(CandidateStep.MAX_BOLUS)))
        assertEquals(listOf("round_step.not_positive"),
            causes(CandidateEngine.calculate(request(roundStep = "0")).step(CandidateStep.ROUNDING)))
    }

    @Test
    fun unconfirmedOrMissingProfileBlocksEveryProfileDependentStep() {
        val unconfirmed = CandidateEngine.calculate(request(profile = Input.Provided(profile(confirmed = false))))
        assertEquals(listOf("profile.unconfirmed"), causes(unconfirmed.step(CandidateStep.MEAL)))
        assertEquals(listOf("profile.unconfirmed"), causes(unconfirmed.step(CandidateStep.CORRECTION)))
        val missing = CandidateEngine.calculate(request(profile = Input.Unavailable("profile.missing")))
        assertEquals(listOf("profile.missing"), causes(missing.step(CandidateStep.MEAL)))
        assertTrue(missing.total is StepOutcome.Blocked)
    }

    @Test
    fun parametersNotConfiguredBlockWithTheirOwnCause() {
        val result = CandidateEngine.calculate(request(profile = Input.Provided(profile(sensitivity = null, target = null))))
        assertEquals(listOf("profile.target.not_configured", "profile.sensitivity.not_configured"),
            causes(result.step(CandidateStep.CORRECTION)))
        assertEquals(6.0, units(result.step(CandidateStep.MEAL)))
    }

    @Test
    fun noUnitIsConverted() {
        val mmolProfile = CandidateEngine.calculate(request(profile = Input.Provided(profile(unit = GlucoseUnitCode.MMOL_L))))
        assertEquals(listOf("profile.glucose_unit.not_traced_for_candidate"), causes(mmolProfile.step(CandidateStep.CORRECTION)))
        val mmolGlucose = CandidateEngine.calculate(request(glucose = Input.Provided(GlucoseValue(q("8"),
            GlucoseUnitCode.MMOL_L, GlucoseOrigin.MANUAL_ENTRY, 0L, false, "manual"))))
        assertEquals(listOf("glucose.unit_not_traced_for_candidate"), causes(mmolGlucose.step(CandidateStep.CORRECTION)))
        val noUnit = CandidateEngine.calculate(request(profile = Input.Provided(profile(unit = null))))
        assertEquals(listOf("profile.glucose_unit.not_configured"), causes(noUnit.step(CandidateStep.CORRECTION)))
    }

    @Test
    fun legacyInvalidRatiosBlockInsteadOfBeingReplaced() {
        assertEquals(listOf("profile.carb_ratio.invalid_for_candidate"), causes(CandidateEngine.calculate(request(
            profile = Input.Provided(profile(carbRatio = "0.1")))).step(CandidateStep.MEAL)))
        assertEquals(listOf("profile.sensitivity.invalid_for_candidate"), causes(CandidateEngine.calculate(request(
            profile = Input.Provided(profile(sensitivity = "5")))).step(CandidateStep.CORRECTION)))
        assertEquals(60.0 / 0.11, units(CandidateEngine.calculate(request(
            profile = Input.Provided(profile(carbRatio = "0.11")))).step(CandidateStep.MEAL)))
    }

    @Test
    fun hypoStopWarnsEvenWhenTheDoseIsBlocked() {
        val low = Input.Provided(GlucoseValue(q("65"), GlucoseUnitCode.MG_DL, GlucoseOrigin.MANUAL_ENTRY, 0L, false, "manual"))
        val blocked = CandidateEngine.calculate(request(glucose = low, iob = null))
        assertTrue(Warnings.BELOW_HYPO_STOP in blocked.warnings)
        assertTrue(blocked.total is StepOutcome.Blocked)
        val computed = CandidateEngine.calculate(request(glucose = low))
        assertEquals(0.0, units(computed.total))
        val atThreshold = CandidateEngine.calculate(request(glucose = Input.Provided(GlucoseValue(q("70"),
            GlucoseUnitCode.MG_DL, GlucoseOrigin.MANUAL_ENTRY, 0L, false, "manual")), carbs = "0"))
        assertFalse(Warnings.BELOW_HYPO_STOP in atThreshold.warnings)
    }

    @Test
    fun segmentIsResolvedFromTheExplicitLocalMinute() {
        val segments = listOf(SegmentValue(0, 720, Input.Provided(q("10"))), SegmentValue(720, 1440, Input.Provided(q("12"))))
        val morning = CandidateEngine.calculate(request(minute = Input.Provided(719),
            profile = Input.Provided(profile(carbRatioSegments = segments))))
        val afternoon = CandidateEngine.calculate(request(minute = Input.Provided(720),
            profile = Input.Provided(profile(carbRatioSegments = segments))))
        assertEquals(6.0, units(morning.step(CandidateStep.MEAL)))
        assertEquals(5.0, units(afternoon.step(CandidateStep.MEAL)))
        val noZone = CandidateEngine.calculate(request(minute = Input.Unavailable("profile.time_zone.not_configured")))
        assertEquals(listOf("profile.time_zone.not_configured"), causes(noZone.step(CandidateStep.MEAL)))
        val gap = listOf(SegmentValue(0, 600, Input.Provided(q("10"))), SegmentValue(700, 1440, Input.Provided(q("12"))))
        assertEquals(listOf("profile.carb_ratio.segments_invalid"), causes(CandidateEngine.calculate(request(
            profile = Input.Provided(profile(carbRatioSegments = gap)))).step(CandidateStep.MEAL)))
    }

    @Test
    fun dexcomGlucoseKeepsItsLimitations() {
        val result = CandidateEngine.calculate(request(glucose = Input.Provided(GlucoseValue(q("190"),
            GlucoseUnitCode.MG_DL, GlucoseOrigin.DEXCOM_PERMISSION_SET, 0L, false, "set:a"))))
        assertTrue(Warnings.DEXCOM_NOT_ATTRIBUTED in result.warnings)
        assertTrue(Warnings.MEASUREMENT_TIME_UNVERIFIED in result.warnings)
        assertTrue(Warnings.FRESHNESS_NOT_APPROVED in result.warnings)
    }

    @Test
    fun fingerprintIsDeterministicAndCoversEveryInput() {
        val base = CandidateEngine.calculate(request())
        assertEquals(base, CandidateEngine.calculate(request()))
        assertEquals(64, base.inputFingerprintSha256.length)
        val variants = listOf(request(carbs = "61"), request(iob = null), request(roundStep = "0.5"),
            request(maxBolus = "14"), request(maxCorrection = "4"), request(minute = Input.Provided(601)),
            request(glucose = Input.Unavailable("glucose.missing")),
            request(profile = Input.Provided(profile(confirmed = false))),
            request(glucose = Input.Provided(GlucoseValue(q("190"), GlucoseUnitCode.MG_DL, GlucoseOrigin.MANUAL_ENTRY,
                1_000L, measurementTimeVerified = false, sourceReference = "other"))))
        variants.forEach { assertNotEquals(base.inputFingerprintSha256, CandidateEngine.calculate(it).inputFingerprintSha256) }
    }

    @Test
    fun quantitiesAndCausesAreCanonical() {
        listOf("01", "1.0", "-1", "1e3", "1,5", "", "1234567", "0.1234").forEach {
            assertFalse(Quantity.isCanonical(it), it)
        }
        listOf("0", "0.05", "15", "123456.789").forEach { assertTrue(Quantity.isCanonical(it), it) }
        assertTrue(runCatching { Input.Unavailable("Missing") }.isFailure)
        assertTrue(runCatching { Input.Unavailable("glucose") }.isFailure)
    }

    @Test
    fun sha256MatchesStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", EngineSha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            EngineSha256.hex("abc".encodeToByteArray()))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            EngineSha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()))
    }
}
