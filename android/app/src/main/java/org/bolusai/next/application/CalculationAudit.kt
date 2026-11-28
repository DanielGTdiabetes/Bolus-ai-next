package org.bolusai.next.application

import org.bolusai.engine.candidate.CandidateCodec
import org.bolusai.engine.candidate.CandidateRequest
import org.bolusai.engine.candidate.CandidateResult
import org.bolusai.engine.candidate.StepOutcome
import org.bolusai.engine.iob.IobOutcome
import org.bolusai.engine.iob.IobResult
import org.bolusai.insulin.CalculationRecord
import java.security.MessageDigest

/**
 * Builds the immutable audit record stored with a dose noted from a test calculation (ADR 0028, AGENTS.md section 6):
 * the canonical request the engine received and a line-per-fact text of what it returned. Nothing here calculates or
 * rounds; doubles are written with their exact shortest representation.
 */
internal object CalculationAudit {
    const val MISMATCH = "calculation_audit.request_does_not_match_result"

    fun record(request: CandidateRequest, result: CandidateResult, iob: IobResult?): CalculationRecord {
        val requestText = CandidateCodec.encode(request)
        // The record must describe one calculation: the request text hashes to the fingerprint the result carries.
        check(sha256(requestText) == result.inputFingerprintSha256) { MISMATCH }
        return CalculationRecord(result.inputFingerprintSha256, requestText, resultText(result, iob))
    }

    private fun resultText(result: CandidateResult, iob: IobResult?): String = buildString {
        fun line(key: String, value: Any) { append(key).append('=').append(value).append('\n') }
        fun outcome(key: String, outcome: StepOutcome) = when (outcome) {
            is StepOutcome.Blocked -> line(key, "blocked:" + outcome.causes.joinToString(","))
            is StepOutcome.Computed -> {
                line(key, outcome.units)
                outcome.facts.forEach { line("$key.fact.${it.key}", it.value) }
            }
        }
        line("codec", "bolus-ai-next/calculation-audit-result/v1")
        line("engine", result.engineVersion)
        line("rule_set", result.ruleSetId)
        line("legacy_sha", result.legacySha)
        line("validation", result.ruleValidation)
        line("usable_for_treatment", result.usableForTreatment)
        line("input_sha256", result.inputFingerprintSha256)
        result.steps.forEach { outcome("step.${it.step.code}", it.outcome) }
        outcome("total", result.total)
        result.warnings.forEach { line("warning", it) }
        result.notApplied.forEach { line("not_applied", it) }
        if (iob != null) {
            line("iob.rule_set", iob.ruleSetId)
            line("iob.legacy_sha", iob.legacySha)
            line("iob.validation", iob.ruleValidation)
            line("iob.calculation_at_ms", iob.calculationAtEpochMs)
            line("iob.input_sha256", iob.inputFingerprintSha256)
            when (val o = iob.outcome) {
                is IobOutcome.Blocked -> line("iob.units", "blocked:" + o.causes.joinToString(","))
                is IobOutcome.Computed -> {
                    line("iob.units", o.units)
                    line("iob.effective_action_min", o.effectiveActionMinutes)
                    line("iob.required_coverage_from_ms", o.requiredCoverageFromEpochMs)
                    line("iob.doses_in_history", o.contributions.size)
                    // Doses with nothing left are in the ledger itself; only those that counted are repeated here.
                    o.contributions.filter { it.remainingUnits > 0.0 }.forEach {
                        line("iob.dose.${it.doseId}", "units:${it.units};elapsed_min:${it.elapsedMinutes};remaining:${it.remainingUnits}")
                    }
                }
            }
            iob.warnings.forEach { line("iob.warning", it) }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
