package org.bolusai.engine.candidate

/**
 * Inputs of the candidate calculation (ADR 0024). Every input is explicit: an absent value is [Input.Unavailable] with
 * the producer's stable cause, never zero and never a default. Nothing here reads a clock, a database or the network.
 */
public sealed interface Input<out T> {
    public data class Provided<out T>(public val value: T) : Input<T>

    /** [cause] is a stable code chosen by the producer, for example `glucose.missing` or `iob.unknown`. */
    public data class Unavailable(public val cause: String) : Input<Nothing> {
        init { require(CAUSE.matches(cause)) { "engine.input.malformed_cause" } }
    }

    private companion object {
        val CAUSE = Regex("[a-z0-9_]+(\\.[a-z0-9_]+){1,5}")
    }
}

/**
 * Non-negative decimal written in the canonical form used by the clinical profile: at most 6 integer and 3 fractional
 * digits, no trailing zeros. It is a representation bound, not a clinical range. The canonical text enters the input
 * fingerprint; [value] is the IEEE double the candidate rules operate on, as Legacy does.
 */
public class Quantity(public val text: String) {
    init { require(PATTERN.matches(text)) { "engine.quantity.not_canonical" } }

    public val value: Double get() = text.toDouble()

    override fun equals(other: Any?): Boolean = other is Quantity && other.text == text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = text

    public companion object {
        private val PATTERN = Regex("^(0|[1-9][0-9]{0,5})(\\.[0-9]{0,2}[1-9])?$")

        public fun isCanonical(text: String): Boolean = PATTERN.matches(text)
    }
}

public enum class GlucoseUnitCode(public val code: String) {
    MG_DL("mg/dL"),
    MMOL_L("mmol/L"),
}

/** Where a glucose value comes from. No origin implies clinical validity. */
public enum class GlucoseOrigin(public val code: String) {
    /** Typed by the user for this calculation. */
    MANUAL_ENTRY("manual_entry"),

    /**
     * Latest Dexcom observation received through the approved permission set (ADR 0023). Not attributed to Dexcom,
     * selected by reception, measurement time unverified until B1 is approved.
     */
    DEXCOM_PERMISSION_SET("dexcom_permission_set"),
}

/**
 * One glucose value as provided. [recordedAtEpochMs] is the instant it was typed or received, not the measurement
 * time. [sourceReference] identifies the exact source within its origin (for Dexcom: policy version, holder set and
 * transmitter fingerprint prefix) so the input fingerprint distinguishes readings that share value and instant. [measurementTimeVerified] stays false until the producer timestamp semantics are approved (ADR 0023, B1).
 * No freshness policy is approved: the engine reports the elapsed time, it never decides that the value is current.
 */
public data class GlucoseValue(
    public val value: Quantity,
    public val unit: GlucoseUnitCode,
    public val origin: GlucoseOrigin,
    public val recordedAtEpochMs: Long,
    public val measurementTimeVerified: Boolean,
    public val sourceReference: String,
) {
    init { require(sourceReference.isNotBlank() && '\n' !in sourceReference) { "engine.glucose.invalid_source_reference" } }
}

public enum class CarbsOrigin(public val code: String) {
    MANUAL_ENTRY("manual_entry"),
    MEAL_REVISION("meal_revision"),
}

public data class CarbsValue(public val grams: Quantity, public val origin: CarbsOrigin)

/** Only manual IOB exists: Next has no insulin history. Its semantics are pending approval (Legacy LC-011). */
public data class IobValue(public val units: Quantity) {
    public val origin: String get() = "manual_entry"
}

/** Local wall-clock interval [startMinute, endMinute) of one profile parameter. [value] absent means not configured. */
public data class SegmentValue(
    public val startMinute: Int,
    public val endMinute: Int,
    public val value: Input<Quantity>,
)

/**
 * The profile version used, copied from the local profile. The engine never reads the profile store: the caller
 * passes the version, its fingerprint and whether its data are confirmed (ADR 0014). Confirmation is not clinical
 * approval.
 */
public data class ProfileSnapshot(
    public val version: Long,
    public val fingerprintSha256: String,
    public val dataConfirmed: Boolean,
    public val glucoseUnit: Input<GlucoseUnitCode>,
    public val timeZoneId: Input<String>,
    public val carbRatio: List<SegmentValue>,
    public val sensitivity: List<SegmentValue>,
    public val target: List<SegmentValue>,
) {
    init {
        require(version > 0) { "engine.profile.invalid_version" }
        require(SHA256.matches(fingerprintSha256)) { "engine.profile.invalid_fingerprint" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Everything one candidate calculation reads. [localMinuteOfDay] is resolved by the platform from
 * [calculationAtEpochMs] and the profile's time zone; it is unavailable when the zone is not configured.
 *
 * [roundStep], [maxBolus] and [maxCorrection] are not part of profile schema 1. They are typed by the user for each
 * test calculation and are recorded as such; absence blocks the steps that need them.
 */
public data class CandidateRequest(
    public val calculationAtEpochMs: Long,
    public val localMinuteOfDay: Input<Int>,
    public val profile: Input<ProfileSnapshot>,
    public val carbs: Input<CarbsValue>,
    public val glucose: Input<GlucoseValue>,
    public val iob: Input<IobValue>,
    public val roundStep: Input<Quantity>,
    public val maxBolus: Input<Quantity>,
    public val maxCorrection: Input<Quantity>,
) {
    init {
        val minute = (localMinuteOfDay as? Input.Provided)?.value
        require(minute == null || minute in 0 until MINUTES_PER_DAY) { "engine.request.invalid_local_minute" }
    }

    public companion object {
        public const val MINUTES_PER_DAY: Int = 1440
    }
}
