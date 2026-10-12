package org.bolusai.engine.iob

import org.bolusai.engine.EngineIdentity
import org.bolusai.engine.candidate.Input
import org.bolusai.engine.candidate.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val NOW = LEGACY_IOB_NOW_MS
private const val HOUR = 3_600_000L

private fun action(
    curve: InsulinCurve = InsulinCurve.WALSH,
    dia: String = "4",
    peak: Input<Int> = Input.Provided(75),
    confirmed: Boolean = true,
) = InsulinAction(1, FINGERPRINT, confirmed, curve, Quantity(dia), peak)

private fun dose(id: String, minutesBefore: Long, units: String, extended: Int = 0) =
    InsulinDose(id, NOW - minutesBefore * 60_000L, Quantity(units), extended)

private fun history(
    doses: List<InsulinDose>,
    from: Long = NOW - 6 * HOUR,
    until: Long = NOW,
    maxExtended: Int = 0,
) = DoseHistory("next-local-history:v1", from, until, maxExtended, doses)

private fun calculate(action: Input<InsulinAction>, history: Input<DoseHistory>, now: Long = NOW) =
    CandidateIob.calculate(IobRequest(now, action, history))

private fun causes(result: IobResult): List<String> = (result.outcome as IobOutcome.Blocked).causes

class CandidateIobTest {
    @Test
    fun matchesEveryLegacyVectorExactly() {
        assertEquals(CandidateIobRules.LEGACY_SHA, LEGACY_IOB_VECTORS_SHA)
        assertEquals(19, LEGACY_IOB_VECTORS.size)
        for (vector in LEGACY_IOB_VECTORS) {
            val curve = InsulinCurve.fromCode(vector.curve) ?: error("unknown curve ${vector.curve}")
            val doses = vector.doses.map {
                InsulinDose(it.id, NOW - it.secondsBefore * 1000L, Quantity(it.units), it.extendedMinutes)
            }
            val maxExtended = doses.maxOfOrNull { it.extendedMinutes } ?: 0
            val result = calculate(
                Input.Provided(action(curve, vector.diaHours, if (curve.usesPeak) Input.Provided(vector.peakMinutes)
                else Input.Unavailable("profile.insulin_peak.not_used_by_curve"))),
                Input.Provided(history(doses, from = NOW - 12 * HOUR, maxExtended = maxExtended)),
            )
            val computed = result.outcome as? IobOutcome.Computed ?: error("${vector.name}: ${result.outcome}")
            assertEquals(vector.totalU.toRawBits(), computed.units.toRawBits(), "${vector.name} total ${computed.units}")
            for (legacy in vector.doses) {
                val mine = computed.contributions.single { it.doseId == legacy.id }
                val expected = legacy.legacyContributionU
                if (expected == null) {
                    assertTrue(mine.remainingUnits <= 0.01, "${vector.name}/${legacy.id} omitted by Legacy")
                } else {
                    assertEquals(expected.toRawBits(), mine.remainingUnits.toRawBits(), "${vector.name}/${legacy.id}")
                }
            }
        }
    }

    @Test
    fun resultIsNeverUsableForTreatmentAndCarriesItsIdentity() {
        val result = calculate(Input.Provided(action()), Input.Provided(history(listOf(dose("a", 30, "2")))))
        assertFalse(result.usableForTreatment)
        assertEquals("pending", result.ruleValidation)
        assertEquals(EngineIdentity.VERSION, result.engineVersion)
        assertEquals("legacy-iob-candidate-v1", result.ruleSetId)
        assertEquals(NOW, result.calculationAtEpochMs)
        assertTrue(IobWarnings.TEST_ONLY in result.warnings)
        assertTrue(IobWarnings.RULES_NOT_APPROVED in result.warnings)
        assertEquals(NOW - 4 * HOUR, (result.outcome as IobOutcome.Computed).requiredCoverageFromEpochMs)
    }

    @Test
    fun emptyCompleteHistoryIsAKnownZeroButUnavailableHistoryIsNever() {
        val zero = calculate(Input.Provided(action()), Input.Provided(history(emptyList())))
        assertEquals(0.0, (zero.outcome as IobOutcome.Computed).units)

        val unknown = calculate(Input.Provided(action()), Input.Unavailable("iob.history.not_recorded"))
        assertEquals(listOf("iob.history.not_recorded"), causes(unknown))
    }

    @Test
    fun actionMustComeFromAConfirmedProfileWithoutDefaults() {
        val h = Input.Provided(history(emptyList()))
        assertEquals(listOf("profile.insulin_action.not_configured"),
            causes(calculate(Input.Unavailable("profile.insulin_action.not_configured"), h)))
        assertEquals(listOf("profile.unconfirmed"), causes(calculate(Input.Provided(action(confirmed = false)), h)))
        assertEquals(listOf("profile.insulin_peak.not_configured"),
            causes(calculate(Input.Provided(action(peak = Input.Unavailable("profile.insulin_peak.not_configured"))), h)))
        assertEquals(listOf("iob.action.dia_not_positive"), causes(calculate(Input.Provided(action(dia = "0")), h)))
    }

    @Test
    fun invalidPeaksBlockInsteadOfLegacyFallbacks() {
        val h = Input.Provided(history(emptyList()))
        assertEquals(listOf("iob.action.peak_not_positive"), causes(calculate(Input.Provided(action(peak = Input.Provided(0))), h)))
        assertEquals(listOf("iob.action.peak_not_before_end"),
            causes(calculate(Input.Provided(action(InsulinCurve.BILINEAR, dia = "2", peak = Input.Provided(120))), h)))
        // Legacy silently switches Walsh to a linear curve when tau <= 0 (peak >= DIA / 2).
        assertEquals(listOf("iob.action.walsh_peak_incompatible"),
            causes(calculate(Input.Provided(action(dia = "2", peak = Input.Provided(60))), h)))
        val bilinearAtHalf = calculate(Input.Provided(action(InsulinCurve.BILINEAR, dia = "2", peak = Input.Provided(60))), h)
        assertTrue(bilinearAtHalf.outcome is IobOutcome.Computed)
    }

    @Test
    fun peakIsReportedAsIgnoredByCurvesThatDoNotUseIt() {
        val result = calculate(Input.Provided(action(InsulinCurve.FIASP, dia = "5")), Input.Provided(history(emptyList())))
        assertTrue(IobWarnings.PEAK_IGNORED_BY_CURVE in result.warnings)
        val withoutPeak = calculate(
            Input.Provided(action(InsulinCurve.LINEAR, peak = Input.Unavailable("profile.insulin_peak.not_used_by_curve"))),
            Input.Provided(history(emptyList())))
        assertTrue(withoutPeak.outcome is IobOutcome.Computed)
        assertFalse(IobWarnings.PEAK_IGNORED_BY_CURVE in withoutPeak.warnings)
    }

    @Test
    fun historyMustCoverDiaPlusDeclaredExtendedDurationUpToNow() {
        val a = Input.Provided(action(dia = "4.5"))
        val exact = calculate(a, Input.Provided(history(emptyList(), from = NOW - 4 * HOUR - HOUR / 2)))
        assertTrue(exact.outcome is IobOutcome.Computed)
        assertEquals(listOf("iob.history.insufficient_window"),
            causes(calculate(a, Input.Provided(history(emptyList(), from = NOW - 4 * HOUR - HOUR / 2 + 1)))))
        assertEquals(listOf("iob.history.insufficient_window"),
            causes(calculate(a, Input.Provided(history(emptyList(), from = NOW - 5 * HOUR, maxExtended = 31)))))
        assertTrue(calculate(a, Input.Provided(history(emptyList(), from = NOW - 5 * HOUR, maxExtended = 30)))
            .outcome is IobOutcome.Computed)
        assertEquals(listOf("iob.history.not_current"),
            causes(calculate(a, Input.Provided(history(emptyList(), until = NOW - 1)))))
        assertEquals(listOf("iob.history.coverage_invalid", "iob.history.not_current", "iob.history.insufficient_window"),
            causes(calculate(a, Input.Provided(history(emptyList(), from = NOW, until = NOW - 1)))))
    }

    @Test
    fun unstretchedTabulatedCurveRequiresItsOwnDurationNotTheShorterDia() {
        // Regression: Legacy keeps the 300 min Fiasp table for a DIA of 4.99 h (299.4 min). A dose 299.7 min ago
        // still leaves insulin, so a history that only covers the DIA could miss an active dose.
        val fiasp = Input.Provided(action(InsulinCurve.FIASP, dia = "4.99"))
        val diaOnly = NOW - 299L * 60_000L - 24_000L
        assertEquals(listOf("iob.history.insufficient_window"),
            causes(calculate(fiasp, Input.Provided(history(emptyList(), from = diaOnly)))))
        val tableEnd = NOW - 300L * 60_000L
        val activeOutsideDia = InsulinDose("a", NOW - 17_982_000L, Quantity("3"), 0)
        val covered = calculate(fiasp, Input.Provided(history(listOf(activeOutsideDia), from = tableEnd)))
        val computed = covered.outcome as IobOutcome.Computed
        assertEquals(tableEnd, computed.requiredCoverageFromEpochMs)
        assertTrue(computed.units > 0.0, "the dose beyond the DIA is still active")
        assertEquals(300.0, computed.effectiveActionMinutes)
        assertTrue(IobWarnings.TABULATED_DURATION_NOT_STRETCHED in covered.warnings)
    }

    @Test
    fun windowIsNeverShorterThanTheDiaAndStretchedTablesEndAtTheDia() {
        val longer = calculate(Input.Provided(action(InsulinCurve.NOVORAPID, dia = "5.01")),
            Input.Provided(history(emptyList())))
        val computed = longer.outcome as IobOutcome.Computed
        assertEquals(NOW - 18_036_000L, computed.requiredCoverageFromEpochMs)
        assertEquals(300.0, computed.effectiveActionMinutes)
        assertTrue(IobWarnings.TABULATED_DURATION_NOT_STRETCHED in longer.warnings)

        for (dia in listOf("5", "4", "6")) {
            val result = calculate(Input.Provided(action(InsulinCurve.FIASP, dia = dia)), Input.Provided(history(emptyList())))
            val outcome = result.outcome as IobOutcome.Computed
            assertEquals(dia.toDouble() * 60, outcome.effectiveActionMinutes, dia)
            assertEquals(NOW - dia.toLong() * HOUR, outcome.requiredCoverageFromEpochMs, dia)
            assertFalse(IobWarnings.TABULATED_DURATION_NOT_STRETCHED in result.warnings, dia)
        }
        val walsh = calculate(Input.Provided(action(dia = "4.99")), Input.Provided(history(emptyList())))
        assertEquals(4.99 * 60, (walsh.outcome as IobOutcome.Computed).effectiveActionMinutes)
        assertEquals(NOW - 17_964_000L, (walsh.outcome as IobOutcome.Computed).requiredCoverageFromEpochMs)
    }

    @Test
    fun anInstantWhoseWindowCannotBeRepresentedBlocksInsteadOfWrapping() {
        // Regression (review of fed1837): near Long.MIN_VALUE the window start wrapped to a large positive instant, so an
        // empty history with no prior coverage gave a known 0 U.
        val start = Long.MIN_VALUE
        val result = calculate(Input.Provided(action()),
            Input.Provided(DoseHistory("next-local-history:v1", start, start, 0, emptyList())), now = start)
        assertEquals(listOf("iob.request.instant_out_of_range"), causes(result))
        // The first instant whose whole window fits still computes.
        val first = Long.MIN_VALUE + 4 * HOUR
        val fits = calculate(Input.Provided(action()),
            Input.Provided(DoseHistory("next-local-history:v1", Long.MIN_VALUE, first, 0, emptyList())), now = first)
        assertEquals(Long.MIN_VALUE, (fits.outcome as IobOutcome.Computed).requiredCoverageFromEpochMs)
        // A dose so old that its elapsed time would wrap blocks too.
        val ancient = InsulinDose("a", Long.MIN_VALUE, Quantity("1"), 0)
        val wrapped = calculate(Input.Provided(action()),
            Input.Provided(DoseHistory("next-local-history:v1", Long.MIN_VALUE, NOW, 0, listOf(ancient))))
        assertEquals(listOf("iob.request.instant_out_of_range"), causes(wrapped))
    }

    @Test
    fun anomalousDosesBlock() {
        val a = Input.Provided(action())
        assertEquals(listOf("iob.history.duplicate_identity"),
            causes(calculate(a, Input.Provided(history(listOf(dose("a", 30, "1"), dose("a", 90, "2")))))))
        assertEquals(listOf("iob.history.future_dose", "iob.history.dose_outside_coverage"),
            causes(calculate(a, Input.Provided(history(listOf(dose("a", -1, "1")))))))
        assertEquals(listOf("iob.history.future_dose"),
            causes(calculate(a, Input.Provided(history(listOf(dose("a", -1, "1")), until = NOW + HOUR)))))
        assertEquals(listOf("iob.history.dose_outside_coverage"),
            causes(calculate(a, Input.Provided(history(listOf(dose("a", 7 * 60, "1")))))))
        assertEquals(listOf("iob.history.non_positive_dose"),
            causes(calculate(a, Input.Provided(history(listOf(dose("a", 30, "0")))))))
        assertEquals(listOf("iob.history.extended_beyond_declared_maximum"),
            causes(calculate(a, Input.Provided(history(listOf(dose("a", 30, "1", extended = 45)), maxExtended = 30)))))
    }

    @Test
    fun sameDoseAndInstantWithDistinctIdentitiesAreDistinctDoses() {
        val one = calculate(Input.Provided(action()), Input.Provided(history(listOf(dose("a", 30, "1")))))
        val two = calculate(Input.Provided(action()), Input.Provided(history(listOf(dose("a", 30, "1"), dose("b", 30, "1")))))
        assertEquals((one.outcome as IobOutcome.Computed).units * 2, (two.outcome as IobOutcome.Computed).units)
    }

    @Test
    fun everyAbsenceIsReportedTogether() {
        val result = calculate(Input.Unavailable("profile.insulin_action.not_configured"),
            Input.Provided(history(listOf(dose("a", -5, "1")))))
        assertEquals(listOf("profile.insulin_action.not_configured", "iob.history.future_dose",
            "iob.history.dose_outside_coverage"), causes(result))
    }

    @Test
    fun undeliveredExtendedChunksCountInFull() {
        val result = calculate(Input.Provided(action()),
            Input.Provided(history(listOf(InsulinDose("a", NOW, Quantity("6"), 60)), maxExtended = 60)))
        assertEquals(6.0, (result.outcome as IobOutcome.Computed).units)
    }

    @Test
    fun fingerprintIsDeterministicAndCoversEveryInput() {
        val base = IobRequest(NOW, Input.Provided(action()), Input.Provided(history(listOf(dose("a", 30, "2")))))
        val fingerprint = CandidateIob.calculate(base).inputFingerprintSha256
        assertEquals(fingerprint, CandidateIob.calculate(base.copy()).inputFingerprintSha256)
        val variants = listOf(
            base.copy(calculationAtEpochMs = NOW + 1),
            base.copy(action = Input.Provided(action(dia = "4.5"))),
            base.copy(action = Input.Provided(action(peak = Input.Provided(76)))),
            base.copy(action = Input.Provided(action(curve = InsulinCurve.BILINEAR))),
            base.copy(action = Input.Provided(action(confirmed = false))),
            base.copy(history = Input.Provided(history(listOf(dose("a", 31, "2"))))),
            base.copy(history = Input.Provided(history(listOf(dose("b", 30, "2"))))),
            base.copy(history = Input.Provided(history(listOf(dose("a", 30, "2.5"))))),
            base.copy(history = Input.Provided(history(listOf(dose("a", 30, "2", extended = 0)), maxExtended = 30))),
            base.copy(history = Input.Provided(history(listOf(dose("a", 30, "2")), from = NOW - 7 * HOUR))),
            base.copy(history = Input.Unavailable("iob.history.not_recorded")),
        )
        for (variant in variants) {
            assertNotEquals(fingerprint, CandidateIob.calculate(variant).inputFingerprintSha256, variant.toString())
        }
    }

    @Test
    fun malformedInputsAreRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { InsulinDose("", NOW, Quantity("1"), 0) }
        assertFailsWith<IllegalArgumentException> { InsulinDose("a:b", NOW, Quantity("1"), 0) }
        assertFailsWith<IllegalArgumentException> { InsulinDose("a", NOW, Quantity("1"), -1) }
        assertFailsWith<IllegalArgumentException> { DoseHistory("", NOW, NOW, 0, emptyList()) }
        assertFailsWith<IllegalArgumentException> { DoseHistory("src", NOW, NOW, -1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { InsulinAction(0, FINGERPRINT, true, InsulinCurve.WALSH, Quantity("4"), Input.Provided(75)) }
        assertFailsWith<IllegalArgumentException> { InsulinAction(1, "x", true, InsulinCurve.WALSH, Quantity("4"), Input.Provided(75)) }
    }
}
