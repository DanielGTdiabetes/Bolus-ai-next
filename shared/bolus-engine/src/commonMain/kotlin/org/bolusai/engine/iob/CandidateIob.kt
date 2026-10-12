package org.bolusai.engine.iob

import org.bolusai.engine.EngineIdentity
import org.bolusai.engine.candidate.EngineSha256
import org.bolusai.engine.candidate.Input
import kotlin.math.ceil
import kotlin.math.max

/**
 * Provenance of the candidate IOB rules (ADR 0026). Observed in Legacy, not approved: [VALIDATION] stays pending and
 * every result is a test result.
 */
public object CandidateIobRules {
    public const val ID: String = "legacy-iob-candidate-v1"
    public const val LEGACY_REPOSITORY: String = "DanielGTdiabetes/bolus_ai"
    public const val LEGACY_SHA: String = "bcac891d85e6fb3e84398c118061e6aeae3f73f1"
    public const val LEGACY_SOURCE: String =
        "backend/app/services/iob.py::compute_iob_from_sources; backend/app/services/math/curves.py::InsulinCurves"
    public const val VALIDATION: String = "pending"

    /** Legacy treats `duration > 10` minutes as an extended bolus and delivers it in 5-minute chunks. */
    public const val LEGACY_EXTENDED_ABOVE_MINUTES: Int = 10
    public const val LEGACY_EXTENDED_CHUNK_MINUTES: Double = 5.0
}

/** Remaining insulin of one dose at the calculation instant, with the elapsed time it was evaluated at. */
public data class DoseContribution(
    public val doseId: String,
    public val units: Double,
    public val elapsedMinutes: Double,
    public val remainingUnits: Double,
)

public sealed interface IobOutcome {
    /**
     * [units] is the IOB, never negative. [contributions] lists every dose of the history, including those with
     * nothing left; Legacy omits contributions of 0.01 U or less from its breakdown but still adds them.
     * [effectiveActionMinutes] is how long the curve really acts for the declared DIA (ADR 0026, §3.1) and
     * [requiredCoverageFromEpochMs] the earliest instant the history had to cover.
     */
    public data class Computed(
        public val units: Double,
        public val contributions: List<DoseContribution>,
        public val effectiveActionMinutes: Double,
        public val requiredCoverageFromEpochMs: Long,
    ) : IobOutcome

    /** Stable causes, never a value. Unknown IOB is never zero. */
    public data class Blocked(public val causes: List<String>) : IobOutcome {
        init { require(causes.isNotEmpty()) { "engine.blocked_without_cause" } }
    }
}

/** Output of one IOB calculation. [usableForTreatment] is always false. */
public data class IobResult(
    public val engineVersion: String,
    public val ruleSetId: String,
    public val legacySha: String,
    public val calculationAtEpochMs: Long,
    public val inputFingerprintSha256: String,
    public val outcome: IobOutcome,
    public val warnings: List<String>,
) {
    public val usableForTreatment: Boolean get() = false
    public val ruleValidation: String get() = CandidateIobRules.VALIDATION
}

/** Stable warning codes of the IOB calculation. They never carry values. */
public object IobWarnings {
    public const val TEST_ONLY: String = "result.test_only_not_for_treatment"
    public const val RULES_NOT_APPROVED: String = "iob.candidate_validation_pending"
    public const val PEAK_IGNORED_BY_CURVE: String = "iob.peak_ignored_by_curve"

    /** A tabulated curve keeps its 300 min because the DIA is within 1 min of it; the window uses the longer one. */
    public const val TABULATED_DURATION_NOT_STRETCHED: String = "iob.action.tabulated_duration_not_stretched"
}

/**
 * Single implementation of the candidate IOB (ADR 0026). Deterministic and pure: no clock, store or network. It
 * computes only from a history the producer vouches for and an insulin action of a confirmed profile version;
 * anything missing, inconsistent or insufficient blocks with stable causes and never becomes `0 U`.
 */
public object CandidateIob {
    private const val MS_PER_MINUTE: Long = 60_000L

    public fun calculate(request: IobRequest): IobResult {
        val warnings = linkedSetOf(IobWarnings.TEST_ONLY, IobWarnings.RULES_NOT_APPROVED)
        val now = request.calculationAtEpochMs
        val causes = mutableListOf<String>()

        val action = when (val a = request.action) {
            is Input.Unavailable -> { causes += a.cause; null }
            is Input.Provided -> a.value
        }
        val durationMs = action?.let { windowMs(it) }
        var peak = 0.0
        if (action != null) {
            if (!action.dataConfirmed) causes += "profile.unconfirmed"
            val durationMinutes = action.diaHours.value * 60
            if (durationMs == 0L) causes += "iob.action.dia_not_positive"
            else if (InsulinCurves.effectiveDurationMinutes(action.curve, durationMinutes) != durationMinutes) {
                warnings += IobWarnings.TABULATED_DURATION_NOT_STRETCHED
            }
            if (action.curve.usesPeak) {
                when (val p = action.peakMinutes) {
                    is Input.Unavailable -> causes += p.cause
                    is Input.Provided -> {
                        peak = p.value.toDouble()
                        when {
                            p.value <= 0 -> causes += "iob.action.peak_not_positive"
                            durationMs != 0L && peak >= durationMinutes -> causes += "iob.action.peak_not_before_end"
                            action.curve == InsulinCurve.WALSH && durationMs != 0L &&
                                (InsulinCurves.walshTau(peak, durationMinutes) <= 0.0 ||
                                    InsulinCurves.walshArea(peak, durationMinutes) == 0.0) ->
                                causes += "iob.action.walsh_peak_incompatible"
                        }
                    }
                }
            } else if (action.peakMinutes is Input.Provided) {
                warnings += IobWarnings.PEAK_IGNORED_BY_CURVE
            }
        }

        val history = when (val h = request.history) {
            is Input.Unavailable -> { causes += h.cause; null }
            is Input.Provided -> h.value
        }
        var requiredFrom = Long.MIN_VALUE
        if (history != null) {
            if (history.coveredFromEpochMs > history.coveredUntilEpochMs) causes += "iob.history.coverage_invalid"
            if (history.coveredUntilEpochMs < now) causes += "iob.history.not_current"
            if (durationMs != null && durationMs > 0L) {
                requiredFrom = now - durationMs - history.maxExtendedMinutes * MS_PER_MINUTE
                if (history.coveredFromEpochMs > requiredFrom) causes += "iob.history.insufficient_window"
            }
            val ids = HashSet<String>()
            for (dose in history.doses) {
                if (!ids.add(dose.id)) causes += "iob.history.duplicate_identity"
                if (dose.administeredAtEpochMs > now) causes += "iob.history.future_dose"
                if (dose.administeredAtEpochMs < history.coveredFromEpochMs ||
                    dose.administeredAtEpochMs > history.coveredUntilEpochMs
                ) causes += "iob.history.dose_outside_coverage"
                if (dose.units.value == 0.0) causes += "iob.history.non_positive_dose"
                if (dose.extendedMinutes > history.maxExtendedMinutes) {
                    causes += "iob.history.extended_beyond_declared_maximum"
                }
            }
        }

        val outcome: IobOutcome = if (causes.isNotEmpty()) {
            IobOutcome.Blocked(causes.distinct())
        } else {
            compute(now, action!!, peak, history!!, requiredFrom)
        }
        return IobResult(
            engineVersion = EngineIdentity.VERSION,
            ruleSetId = CandidateIobRules.ID,
            legacySha = CandidateIobRules.LEGACY_SHA,
            calculationAtEpochMs = now,
            inputFingerprintSha256 = EngineSha256.hex(IobCodec.encode(request).encodeToByteArray()),
            outcome = outcome,
            warnings = warnings.toList(),
        )
    }

    /** Legacy loop of `compute_iob_from_sources`, in input order, with its square-wave chunking. */
    private fun compute(now: Long, action: InsulinAction, peak: Double, history: DoseHistory, requiredFrom: Long):
        IobOutcome.Computed {
        val duration = action.diaHours.value * 60
        var total = 0.0
        val contributions = history.doses.map { dose ->
            val units = dose.units.value
            // Python `(now - ts).total_seconds() / 60`: exact seconds first, then minutes.
            val elapsed = ((now - dose.administeredAtEpochMs).toDouble() / 1000.0) / 60
            var contribution = 0.0
            if (dose.extendedMinutes > CandidateIobRules.LEGACY_EXTENDED_ABOVE_MINUTES) {
                val step = CandidateIobRules.LEGACY_EXTENDED_CHUNK_MINUTES
                val chunks = ceil(dose.extendedMinutes / step).toInt()
                val perChunk = units / chunks
                for (k in 0 until chunks) {
                    val sinceChunk = elapsed - k * step
                    // Undelivered chunks count in full, as in Legacy.
                    contribution += if (sinceChunk < 0) perChunk
                    else perChunk * InsulinCurves.remaining(action.curve, sinceChunk, duration, peak)
                }
            } else {
                contribution = max(units * InsulinCurves.remaining(action.curve, elapsed, duration, peak), 0.0)
            }
            total += contribution
            DoseContribution(dose.id, units, elapsed, contribution)
        }
        val effective = InsulinCurves.effectiveDurationMinutes(action.curve, duration)
        return IobOutcome.Computed(max(total, 0.0), contributions, effective, requiredFrom)
    }

    /**
     * Action window in milliseconds: the DIA, or the curve's own end when it acts longer (an unstretched table). It is
     * never shorter than the DIA. The DIA is exact from the canonical text (at most 3 decimals), so no IEEE error
     * moves the window; the table end is a whole number of minutes.
     */
    private fun windowMs(action: InsulinAction): Long {
        val parts = action.diaHours.text.split('.')
        val thousandths = parts[0].toLong() * 1000 + (parts.getOrNull(1)?.padEnd(3, '0')?.toLong() ?: 0L)
        val diaMs = thousandths * 3_600L
        if (diaMs == 0L) return 0L
        val effectiveMinutes = InsulinCurves.effectiveDurationMinutes(action.curve, action.diaHours.value * 60)
        return max(diaMs, effectiveMinutes.toLong() * MS_PER_MINUTE)
    }
}

/** Canonical text of an IOB request: the fingerprint covers the instant, the action and every dose. */
public object IobCodec {
    private const val MISSING = "~"

    public fun encode(request: IobRequest): String = buildString {
        fun line(key: String, value: String) { append(key).append('=').append(value).append('\n') }
        fun <T> input(key: String, input: Input<T>, render: (T) -> String) = line(key, when (input) {
            is Input.Unavailable -> "$MISSING${input.cause}"
            is Input.Provided -> render(input.value)
        })
        line("codec", "bolus-ai-next/iob-request/v1")
        line("engine", EngineIdentity.VERSION)
        line("rule_set", CandidateIobRules.ID)
        line("calculation_at_ms", request.calculationAtEpochMs.toString())
        input("action", request.action) {
            "v${it.profileVersion}:${it.profileFingerprintSha256}:confirmed=${it.dataConfirmed}:" +
                "${it.curve.code}:dia_h=${it.diaHours.text}"
        }
        (request.action as? Input.Provided)?.value?.let { input("action.peak_min", it.peakMinutes) { p -> p.toString() } }
        input("history", request.history) {
            "${it.sourceReference}:${it.coveredFromEpochMs}-${it.coveredUntilEpochMs}:max_ext_min=${it.maxExtendedMinutes}"
        }
        (request.history as? Input.Provided)?.value?.doses?.forEachIndexed { index, dose ->
            line("dose.$index", "${dose.id}:${dose.administeredAtEpochMs}:${dose.units.text}:ext_min=${dose.extendedMinutes}")
        }
    }
}
