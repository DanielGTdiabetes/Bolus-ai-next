package org.bolusai.tools.shadow

import org.bolusai.engine.shadow.ShadowClass
import org.bolusai.engine.shadow.ShadowReplay
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0030: the committed synthetic export replays into the expected classes. No real data. */
class ShadowReportTest {
    private val input = ShadowReplay.parse(
        File(System.getProperty("shadow.fixtures"), "synthetic-expected.txt").readText(Charsets.UTF_8))
    private val comparisons = ShadowReplay.compareAll(input)

    @Test fun theSyntheticExportIsClassifiedRecordByRecord() {
        assertEquals(listOf(ShadowClass.MATCH, ShadowClass.MATCH, ShadowClass.MATCH, ShadowClass.NOT_COMPARABLE,
            ShadowClass.NOT_COMPARABLE, ShadowClass.MISMATCH, ShadowClass.MATCH_STRATEGY_INFERRED,
            ShadowClass.MISMATCH), comparisons.map { it.shadowClass })
        assertEquals(listOf("legacy.glucose_stale"), comparisons[3].reasons)
        assertEquals(listOf("legacy.exercise_reduction_applied"), comparisons[4].reasons)
        assertEquals(listOf("differs.total_u"), comparisons[5].reasons)
        assertEquals(listOf("warsaw_strategy.normal_inferred", "settings.unverified_for_nutrients"), comparisons[6].reasons)
        // Autosens 1.1 with unadjusted ratios in the synthetic row: replayed, and the difference is reported.
        assertTrue("autosens.effective_ratios_replayed" in comparisons[7].reasons)
    }

    @Test fun theSummaryHasCountsAndNoClinicalValue() {
        val summary = ShadowReport.summary(input, comparisons)
        assertTrue("- Calculations: 8; comparable: 6; matches: 4" in summary.lines())
        assertTrue("| MISMATCH | 2 |" in summary.lines())
        assertTrue("| NOT_COMPARABLE:legacy.glucose_stale | 1 |" in summary.lines())
        assertTrue("not usable for treatment" in summary)
        // No dose, gram or glucose value of the records appears: only counts and codes.
        listOf("8.4", "8.6", "190", "legacy 8", "next 8").forEach { assertFalse(it in summary, it) }
        comparisons.forEach { assertFalse(it.reference in summary) }
    }

    @Test fun theDetailsListOnlyWhatIsNotAPlainMatchAndSayTheyAreClinical() {
        val details = ShadowReport.details(comparisons)
        assertTrue(details.startsWith("# Shadow replay details: CLINICAL VALUES"))
        assertEquals(5, details.lines().count { it.startsWith("## ") })
        assertTrue(details.lines().any { it.endsWith(": MISMATCH") })
        assertTrue("- total_u: legacy 8.4, next 8.0  <-- differs" in details.lines())
        assertFalse(comparisons.first().reference in details)
    }
}
