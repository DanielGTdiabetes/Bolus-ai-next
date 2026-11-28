package org.bolusai.tools.shadow

import org.bolusai.engine.EngineIdentity
import org.bolusai.engine.candidate.CandidateRuleSet
import org.bolusai.engine.shadow.ShadowClass
import org.bolusai.engine.shadow.ShadowComparison
import org.bolusai.engine.shadow.ShadowInput
import org.bolusai.engine.shadow.ShadowReplay
import java.io.File
import kotlin.system.exitProcess

/**
 * Shadow replay report (ADR 0030). Reads the minimised text written by
 * `scripts/shadow/extract_legacy_calculations.py`, replays every stored Legacy calculation through the single
 * candidate engine and prints counts. It reads one local file and writes at most one: no network, no database, no
 * treatment and nothing sent to Legacy.
 */
object ShadowReport {
    /** Counts only: classes and stable reason codes. Safe to keep; it carries no clinical value. */
    fun summary(input: ShadowInput, comparisons: List<ShadowComparison>): String = buildString {
        val summary = ShadowReplay.summary(comparisons)
        appendLine("# Shadow replay of stored Legacy calculations")
        appendLine()
        appendLine("- Engine: ${EngineIdentity.VERSION}, rule set ${CandidateRuleSet.ID}, rules pinned at Legacy ${CandidateRuleSet.LEGACY_SHA}")
        appendLine("- Legacy audited at: ${input.legacySha}")
        appendLine("- Results are test results: not usable for treatment, rule validation pending")
        appendLine("- Calculations: ${summary.total}; comparable: ${summary.comparable}; matches: ${summary.matches}")
        appendLine()
        appendLine("| Class | Count |")
        appendLine("|---|---|")
        ShadowClass.entries.forEach { appendLine("| ${it.name} | ${summary.byClass[it] ?: 0} |") }
        appendLine()
        appendLine("| Class and reason | Count |")
        appendLine("|---|---|")
        summary.byReason.forEach { (reason, count) -> appendLine("| $reason | $count |") }
    }

    /** Every record that is not an unqualified match, with its numbers. Clinical values: keep outside the repository. */
    fun details(comparisons: List<ShadowComparison>): String = buildString {
        appendLine("# Shadow replay details: CLINICAL VALUES, do not commit or share")
        comparisons.filter { it.shadowClass != ShadowClass.MATCH }.forEach { comparison ->
            appendLine()
            appendLine("## ${comparison.reference}: ${comparison.shadowClass.name}")
            comparison.reasons.forEach { appendLine("- $it") }
            comparison.components.forEach {
                appendLine("- ${it.name}: legacy ${it.legacy}, next ${it.next}" + if (it.equal) "" else "  <-- differs")
            }
        }
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.size > 2) {
        System.err.println("Usage: shadow-replay <minimised-input.txt> [details-output.md]")
        exitProcess(2)
    }
    val input = ShadowReplay.parse(File(args[0]).readText(Charsets.UTF_8))
    val comparisons = ShadowReplay.compareAll(input)
    print(ShadowReport.summary(input, comparisons))
    args.getOrNull(1)?.let { path ->
        File(path).writeText(ShadowReport.details(comparisons), Charsets.UTF_8)
        System.err.println("Details with clinical values written to $path: keep them outside the repository.")
    }
}
