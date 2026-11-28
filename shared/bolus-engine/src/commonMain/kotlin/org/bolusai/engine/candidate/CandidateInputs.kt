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

/**
 * Fibre, fat and protein of the meal, in grams (ADR 0029). Each is explicit: an unknown amount is unavailable and is
 * never taken as zero. `0` is a declared fact: the meal has none.
 */
public data class MealNutrition(
    public val fiber: Input<Quantity>,
    public val fat: Input<Quantity>,
    public val protein: Input<Quantity>,
) {
    public companion object {
        /** Nothing declared: the rules that would read each amount block. */
        public val UNKNOWN: MealNutrition = MealNutrition(Input.Unavailable("meal.fiber.missing"),
            Input.Unavailable("meal.fat.missing"), Input.Unavailable("meal.protein.missing"))
    }
}

/** Legacy `BolusRequestV2.strategy`: `auto` lets the Warsaw trigger select the dual factor, `normal` never does. */
public enum class WarsawStrategy(public val code: String) {
    AUTO("auto"),
    NORMAL("normal"),
}

/**
 * Fibre and Warsaw settings of the profile version used (content schema 4, ADR 0029). Legacy has two switches
 * (`calculator.subtract_fiber`, `warsaw.enabled`); Next has none: a factor of `0` gives exactly the Legacy result
 * with the switch off, so switching a rule off is declaring its factor as `0`. Nothing is defaulted: Legacy
 * defaults (0.5, 5 g, 0.1, 0.2, 300 kcal) are never used.
 */
public data class NutritionRules(
    public val fiberFactor: Input<Quantity>,
    public val fiberThresholdG: Input<Quantity>,
    public val warsawFactorSimple: Input<Quantity>,
    public val warsawFactorDual: Input<Quantity>,
    public val warsawTriggerKcal: Input<Quantity>,
) {
    public companion object {
        /** A profile version whose schema has no nutrition rules: unknown, never filled in. */
        public val NOT_IN_SCHEMA: NutritionRules = NutritionRules(
            Input.Unavailable("profile.fiber_factor.not_in_schema"),
            Input.Unavailable("profile.fiber_threshold.not_in_schema"),
            Input.Unavailable("profile.warsaw_factor_simple.not_in_schema"),
            Input.Unavailable("profile.warsaw_factor_dual.not_in_schema"),
            Input.Unavailable("profile.warsaw_trigger.not_in_schema"))
    }
}

/**
 * IOB as provided, with its provenance. A manual value is typed by the user (semantics pending, Legacy LC-011). A
 * calculated value comes from the candidate IOB over a dose history (ADR 0026, ADR 0028): it keeps the exact double
 * the IOB rules produced, without rounding, and [reference] is the input fingerprint of that IOB calculation, which
 * determines the value. [reference] and [origin] enter the fingerprint of the bolus calculation.
 */
public class IobValue private constructor(
    public val unitsValue: Double,
    public val origin: String,
    public val reference: String,
) {
    /** Manual entry: the canonical text is the reference, as before ADR 0028. */
    public constructor(units: Quantity) : this(units.value, MANUAL_ENTRY, units.text)

    override fun equals(other: Any?): Boolean =
        other is IobValue && other.unitsValue == unitsValue && other.origin == origin && other.reference == reference
    override fun hashCode(): Int = 31 * (31 * unitsValue.hashCode() + origin.hashCode()) + reference.hashCode()
    override fun toString(): String = "IobValue($unitsValue, $origin, $reference)"

    public companion object {
        public const val MANUAL_ENTRY: String = "manual_entry"
        public const val CALCULATED: String = "calculated_from_dose_history"
        public const val REPLAYED: String = "legacy_trace_replay"
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** [units] must be a finite, non-negative result of the candidate IOB identified by its input fingerprint. */
        public fun calculated(units: Double, iobInputFingerprintSha256: String): IobValue {
            require(units.isFinite() && units >= 0.0) { "engine.iob_value.invalid_units" }
            require(SHA256.matches(iobInputFingerprintSha256)) { "engine.iob_value.invalid_fingerprint" }
            return IobValue(units, CALCULATED, iobInputFingerprintSha256)
        }

        /**
         * The IOB a stored Legacy calculation used, for the shadow replay only (ADR 0030). [reference] identifies
         * the replayed record. It is never an input of a calculation shown to the user.
         */
        public fun replayed(units: Double, reference: String): IobValue {
            require(units.isFinite() && units >= 0.0) { "engine.iob_value.invalid_units" }
            require(reference.isNotBlank() && '\n' !in reference) { "engine.iob_value.invalid_reference" }
            return IobValue(units, REPLAYED, "$reference:$units")
        }
    }
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
 *
 * [roundStep], [maxBolus] and [maxCorrection] are the profile-wide limits of that same version (content schema 2,
 * ADR 0025), in insulin units. A version without them carries the producer's cause, for example
 * `profile.round_step.not_in_schema` for schema 1 or `profile.round_step.not_configured`; nothing is filled in.
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
    public val roundStep: Input<Quantity>,
    public val maxBolus: Input<Quantity>,
    public val maxCorrection: Input<Quantity>,
    public val nutritionRules: NutritionRules = NutritionRules.NOT_IN_SCHEMA,
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
 * Rounding step, maximum bolus and maximum correction come only from [profile] (ADR 0025).
 */
public data class CandidateRequest(
    public val calculationAtEpochMs: Long,
    public val localMinuteOfDay: Input<Int>,
    public val profile: Input<ProfileSnapshot>,
    public val carbs: Input<CarbsValue>,
    public val glucose: Input<GlucoseValue>,
    public val iob: Input<IobValue>,
    public val nutrition: MealNutrition = MealNutrition.UNKNOWN,
    public val warsawStrategy: WarsawStrategy = WarsawStrategy.AUTO,
) {
    init {
        val minute = (localMinuteOfDay as? Input.Provided)?.value
        require(minute == null || minute in 0 until MINUTES_PER_DAY) { "engine.request.invalid_local_minute" }
    }

    public companion object {
        public const val MINUTES_PER_DAY: Int = 1440
    }
}
