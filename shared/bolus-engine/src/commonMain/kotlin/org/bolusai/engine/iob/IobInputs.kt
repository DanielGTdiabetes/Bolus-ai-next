package org.bolusai.engine.iob

import org.bolusai.engine.candidate.Input
import org.bolusai.engine.candidate.Quantity

/**
 * Insulin action curves observed in Legacy `InsulinCurves.get_iob` (ADR 0026). [usesPeak] says whether the curve reads
 * the peak time; the interpolated Fiasp and NovoRapid tables and the linear curve ignore it, as in Legacy.
 */
public enum class InsulinCurve(public val code: String, public val usesPeak: Boolean) {
    WALSH("walsh", true),
    BILINEAR("bilinear", true),
    FIASP("fiasp", false),
    NOVORAPID("novorapid", false),
    LINEAR("linear", false),
    ;

    public companion object {
        public fun fromCode(code: String): InsulinCurve? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Insulin action of one profile version. The engine never reads the profile store: the caller copies the version,
 * its fingerprint and whether its data are confirmed. [diaHours] is the duration of insulin action in hours;
 * [peakMinutes] is unavailable when the curve does not use it or it is not configured. Nothing is filled in:
 * Legacy defaults (4 h, Walsh, 75 min) are not used.
 */
public data class InsulinAction(
    public val profileVersion: Long,
    public val profileFingerprintSha256: String,
    public val dataConfirmed: Boolean,
    public val curve: InsulinCurve,
    public val diaHours: Quantity,
    public val peakMinutes: Input<Int>,
) {
    init {
        require(profileVersion > 0) { "engine.iob.invalid_profile_version" }
        require(SHA256.matches(profileFingerprintSha256)) { "engine.iob.invalid_profile_fingerprint" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * One rapid-acting bolus as recorded by the history producer. Basal or long-acting insulin never enters this list: the
 * producer separates it by type, not by text as Legacy does. [extendedMinutes] is the delivery duration of an
 * extended (square-wave) bolus; `0` is an ordinary bolus, a recorded fact rather than an absent value.
 */
public data class InsulinDose(
    public val id: String,
    public val administeredAtEpochMs: Long,
    public val units: Quantity,
    public val extendedMinutes: Int,
) {
    init {
        require(id.isNotBlank() && '\n' !in id && ':' !in id) { "engine.iob.invalid_dose_id" }
        require(extendedMinutes >= 0) { "engine.iob.negative_extended_minutes" }
    }
}

/**
 * The dose history the producer vouches for. It declares the interval it covers completely
 * [[coveredFromEpochMs], [coveredUntilEpochMs]] and the longest extended duration it can record
 * ([maxExtendedMinutes]); the engine checks that this interval reaches far enough back for the action used. A
 * producer that cannot vouch for completeness must pass [Input.Unavailable] instead of an empty list.
 */
public data class DoseHistory(
    public val sourceReference: String,
    public val coveredFromEpochMs: Long,
    public val coveredUntilEpochMs: Long,
    public val maxExtendedMinutes: Int,
    public val doses: List<InsulinDose>,
) {
    init {
        require(sourceReference.isNotBlank() && '\n' !in sourceReference) { "engine.iob.invalid_source_reference" }
        require(maxExtendedMinutes >= 0) { "engine.iob.negative_max_extended_minutes" }
    }
}

/** Everything one IOB calculation reads. [calculationAtEpochMs] is injected by the caller; the engine has no clock. */
public data class IobRequest(
    public val calculationAtEpochMs: Long,
    public val action: Input<InsulinAction>,
    public val history: Input<DoseHistory>,
)
