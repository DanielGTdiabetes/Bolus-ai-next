package org.bolusai.profile

/** A profile-wide declaration. [NotConfigured] is explicit and never replaced by a default. */
sealed interface Setting<out T> {
    data object NotConfigured : Setting<Nothing>
    data class Declared<T>(val value: T) : Setting<T>
}

/** Declared glucose unit. Values are never converted between units. */
enum class GlucoseUnit(val code: String) {
    MG_DL("mg/dL"), MMOL_L("mmol/L");

    companion object {
        fun fromCode(code: String): GlucoseUnit? = entries.firstOrNull { it.code == code }
    }
}

/** IANA region identifier. Existence is checked by the platform before saving; reads check grammar only. */
data class ProfileTimeZone(val id: String) {
    init { require(isWellFormed(id)) { "profile.time_zone.malformed" } }

    companion object {
        private val PATTERN = Regex("^[A-Za-z][A-Za-z0-9_+\\-]*(/[A-Za-z0-9_+\\-]+){0,3}$")
        fun isWellFormed(id: String): Boolean = id.length in 1..64 && PATTERN.matches(id)
    }
}

/** Parameters whose meaning depends on the declared glucose unit are [glucoseDependent]. */
enum class ProfileParameter(val code: String, val glucoseDependent: Boolean) {
    CARB_RATIO("carb_ratio", false),
    INSULIN_SENSITIVITY("insulin_sensitivity", true),
    GLUCOSE_TARGET("glucose_target", true);

    /** Unit label derived from the catalog and the version's own glucose unit; null when it cannot be stated. */
    fun unitLabel(unit: Setting<GlucoseUnit>): String? = when (this) {
        CARB_RATIO -> "g/U"
        INSULIN_SENSITIVITY -> (unit as? Setting.Declared)?.let { "${it.value.code}/U" }
        GLUCOSE_TARGET -> (unit as? Setting.Declared)?.value?.code
    }

    companion object {
        fun fromCode(code: String): ProfileParameter? = entries.firstOrNull { it.code == code }
    }
}

/** What a profile-wide field is for. The UI groups them; storage, codec and confirmation treat them alike. */
enum class ProfileFieldGroup { CALCULATION_LIMITS, INSULIN_ACTION }

/**
 * Profile-wide numeric fields, one value per version, never per time segment and never dependent on the glucose unit.
 * No range is checked here.
 *
 * - Content schema 2 (ADR 0025): the insulin limits of Legacy `UserSettings` (`round_step_u`, `max_bolus_u`,
 *   `max_correction_u`), in U.
 * - Content schema 3 (ADR 0027): the insulin action of Legacy `IOBConfig` (`dia_hours`, `peak_minutes`). Legacy
 *   defaults (4 h, 75 min) are never used. The peak is whole minutes, as Legacy stores it ([wholeNumber]); that is a
 *   representation rule, not a clinical range. The curve is not numeric and lives in [ProfileContent.insulinCurve].
 */
enum class ProfileLimit(val code: String, val unitLabel: String, val group: ProfileFieldGroup, val wholeNumber: Boolean) {
    ROUND_STEP("round_step", "U", ProfileFieldGroup.CALCULATION_LIMITS, false),
    MAX_BOLUS("max_bolus", "U", ProfileFieldGroup.CALCULATION_LIMITS, false),
    MAX_CORRECTION("max_correction", "U", ProfileFieldGroup.CALCULATION_LIMITS, false),
    INSULIN_DIA("insulin_dia", "h", ProfileFieldGroup.INSULIN_ACTION, false),
    INSULIN_PEAK("insulin_peak", "min", ProfileFieldGroup.INSULIN_ACTION, true),
    ;

    /** Representation check only: a [wholeNumber] field rejects a fractional value. */
    fun accepts(value: ProfileValue): Boolean =
        !wholeNumber || value !is ProfileValue.Entered || '.' !in value.decimal.text

    companion object {
        fun fromCode(code: String): ProfileLimit? = entries.firstOrNull { it.code == code }

        fun of(group: ProfileFieldGroup): List<ProfileLimit> = entries.filter { it.group == group }
    }
}

/**
 * Insulin action curve of content schema 3 (ADR 0027), with the names Legacy `IOBConfig.curve` accepts. [usesPeak]
 * mirrors the engine: the tabulated curves and the linear one ignore the peak. Choosing a curve is a user
 * declaration, never a default.
 */
enum class InsulinCurveCode(val code: String, val usesPeak: Boolean) {
    WALSH("walsh", true),
    BILINEAR("bilinear", true),
    FIASP("fiasp", false),
    NOVORAPID("novorapid", false),
    LINEAR("linear", false),
    ;

    companion object {
        fun fromCode(code: String): InsulinCurveCode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Content schema catalogs. A new parameter or field is a new content schema, never a change to an earlier one:
 * schema 1 has no limits and schemas 1 and 2 have no insulin action, so those versions keep them unknown forever and
 * are never filled in.
 */
object ProfileCatalog {
    const val CURRENT_SCHEMA = 3
    const val MINUTES_PER_DAY = 1440
    private val schema1 = listOf(ProfileParameter.CARB_RATIO, ProfileParameter.INSULIN_SENSITIVITY,
        ProfileParameter.GLUCOSE_TARGET)
    private val limits2 = listOf(ProfileLimit.ROUND_STEP, ProfileLimit.MAX_BOLUS, ProfileLimit.MAX_CORRECTION)
    private val limits3 = limits2 + listOf(ProfileLimit.INSULIN_DIA, ProfileLimit.INSULIN_PEAK)

    fun parameters(schemaVersion: Int): List<ProfileParameter>? = if (schemaVersion in 1..CURRENT_SCHEMA) schema1 else null

    fun limits(schemaVersion: Int): List<ProfileLimit>? = when (schemaVersion) {
        1 -> emptyList()
        2 -> limits2
        3 -> limits3
        else -> null
    }

    /** Whether [schemaVersion] declares the insulin curve (ADR 0027). */
    fun hasInsulinCurve(schemaVersion: Int): Boolean = schemaVersion >= 3

    /** Smallest known schema, at least [from], that holds [limit]; null when none does. */
    fun firstSchemaWith(limit: ProfileLimit, from: Int): Int? =
        (from..CURRENT_SCHEMA).firstOrNull { limit in limits(it).orEmpty() }

    /** Every limit of [schemaVersion] explicitly not configured; empty for a schema without limits. */
    fun unconfiguredLimits(schemaVersion: Int): Map<ProfileLimit, ProfileValue> =
        limits(schemaVersion).orEmpty().associateWith { ProfileValue.NotConfigured }
}

sealed interface ProfileValue {
    data object NotConfigured : ProfileValue
    data class Entered(val decimal: CanonicalDecimal) : ProfileValue
}

/** Local wall-clock interval [startMinute, endMinute) in the version's time zone. Never crosses midnight. */
data class TimeSegment(val startMinute: Int, val endMinute: Int, val value: ProfileValue) {
    init {
        require(startMinute in 0 until ProfileCatalog.MINUTES_PER_DAY) { "profile.segment.start" }
        require(endMinute in 1..ProfileCatalog.MINUTES_PER_DAY && endMinute > startMinute) { "profile.segment.end" }
    }
    val coversWholeDay: Boolean get() = startMinute == 0 && endMinute == ProfileCatalog.MINUTES_PER_DAY
}

data class ParameterSchedule(val parameter: ProfileParameter, val segments: List<TimeSegment>) {
    val singleAllDay: TimeSegment? get() = segments.singleOrNull()?.takeIf { it.coversWholeDay }
    val hasEnteredValue: Boolean get() = segments.any { it.value is ProfileValue.Entered }

    companion object {
        fun allDay(parameter: ProfileParameter, value: ProfileValue = ProfileValue.NotConfigured) =
            ParameterSchedule(parameter, listOf(TimeSegment(0, ProfileCatalog.MINUTES_PER_DAY, value)))
    }
}

/**
 * Content of one profile version. Construction enforces the structural invariants of ADR 0012:
 * catalog complete and ordered, full-day contiguous segments, and no unit-dependent value without a unit.
 * [limits] holds exactly the profile-wide fields of the schema (none in schema 1); the default marks them not
 * configured. [insulinCurve] is null when the schema has no curve (schemas 1 and 2), which means unknown.
 * No clinical range is checked.
 */
data class ProfileContent(
    val schemaVersion: Int,
    val glucoseUnit: Setting<GlucoseUnit>,
    val timeZone: Setting<ProfileTimeZone>,
    val schedules: List<ParameterSchedule>,
    val limits: Map<ProfileLimit, ProfileValue> = ProfileCatalog.unconfiguredLimits(schemaVersion),
    val insulinCurve: Setting<InsulinCurveCode>? =
        if (ProfileCatalog.hasInsulinCurve(schemaVersion)) Setting.NotConfigured else null,
) {
    init {
        val problem = problem(schemaVersion, glucoseUnit, schedules)
        require(problem == null) { problem?.code ?: "" }
        require(limits.keys == ProfileCatalog.limits(schemaVersion).orEmpty().toSet()) {
            ProfileFailure.INVALID_PARAMETERS.code
        }
        require((insulinCurve != null) == ProfileCatalog.hasInsulinCurve(schemaVersion)) {
            ProfileFailure.INVALID_PARAMETERS.code
        }
        require(limits.all { (limit, value) -> limit.accepts(value) }) { ProfileFailure.NOT_WHOLE_NUMBER.code }
    }

    fun schedule(parameter: ProfileParameter): ParameterSchedule = schedules.first { it.parameter == parameter }

    /** The stored value of [limit]; null when this version's schema has no such field, so it is unknown. */
    fun limit(limit: ProfileLimit): ProfileValue? = limits[limit]

    /**
     * Sets one profile-wide field. When this schema has no such field, entering a value moves the content to the
     * smallest schema that has it: its own values are kept and every new field starts not configured, nothing is
     * copied from elsewhere. Clearing a field the schema does not have changes nothing.
     */
    fun withLimit(limit: ProfileLimit, value: ProfileValue): ProfileContent = when {
        limit in limits -> copy(limits = limits + (limit to value))
        value is ProfileValue.NotConfigured -> this
        else -> upgradedTo(requireNotNull(ProfileCatalog.firstSchemaWith(limit, schemaVersion + 1)))
            .withLimit(limit, value)
    }

    /** Declares the insulin curve (ADR 0027). A schema without a curve moves to the first schema that has one. */
    fun withInsulinCurve(curve: Setting<InsulinCurveCode>): ProfileContent = when {
        insulinCurve != null -> copy(insulinCurve = curve)
        curve is Setting.NotConfigured -> this
        else -> upgradedTo((schemaVersion + 1..ProfileCatalog.CURRENT_SCHEMA).first { ProfileCatalog.hasInsulinCurve(it) })
            .withInsulinCurve(curve)
    }

    private fun upgradedTo(schema: Int): ProfileContent = ProfileContent(schema, glucoseUnit, timeZone, schedules,
        ProfileCatalog.unconfiguredLimits(schema) + limits,
        insulinCurve ?: if (ProfileCatalog.hasInsulinCurve(schema)) Setting.NotConfigured else null)

    /**
     * The same content in the smallest schema, at least [minimum], that keeps every configured value; this content
     * when no smaller schema does. Lets an editor drop a schema it only entered to type a value that was then cleared.
     */
    fun compactedTo(minimum: Int): ProfileContent {
        for (schema in minimum until schemaVersion) {
            val catalog = ProfileCatalog.limits(schema) ?: continue
            val dropsValue = limits.any { (limit, value) -> limit !in catalog && value is ProfileValue.Entered }
            val dropsCurve = !ProfileCatalog.hasInsulinCurve(schema) && insulinCurve is Setting.Declared
            if (!dropsValue && !dropsCurve) {
                return ProfileContent(schema, glucoseUnit, timeZone, schedules, limits.filterKeys { it in catalog },
                    if (ProfileCatalog.hasInsulinCurve(schema)) insulinCurve else null)
            }
        }
        return this
    }

    val hasGlucoseDependentValues: Boolean
        get() = schedules.any { it.parameter.glucoseDependent && it.hasEnteredValue }

    /**
     * Declares a unit. When it differs from the current one, every segment of every glucose-dependent schedule becomes
     * not configured while its boundaries stay exactly as they are (ADR 0013, section 6). Values typed under one unit
     * are never kept under another, nothing is converted and no segment is merged, even when all end up equal.
     */
    fun withGlucoseUnit(unit: Setting<GlucoseUnit>): ProfileContent =
        if (unit == glucoseUnit) this
        else copy(glucoseUnit = unit, schedules = schedules.map { schedule ->
            if (!schedule.parameter.glucoseDependent) schedule
            else schedule.copy(segments = schedule.segments.map { it.copy(value = ProfileValue.NotConfigured) })
        })

    fun withTimeZone(zone: Setting<ProfileTimeZone>): ProfileContent = copy(timeZone = zone)

    companion object {
        /** A new profile: every value and declaration not configured, one full-day segment per parameter. */
        fun empty(): ProfileContent = ProfileContent(ProfileCatalog.CURRENT_SCHEMA, Setting.NotConfigured,
            Setting.NotConfigured, ProfileCatalog.parameters(ProfileCatalog.CURRENT_SCHEMA)!!.map {
                ParameterSchedule.allDay(it)
            })

        fun problem(schemaVersion: Int, unit: Setting<GlucoseUnit>, schedules: List<ParameterSchedule>): ProfileFailure? {
            val catalog = ProfileCatalog.parameters(schemaVersion) ?: return ProfileFailure.INVALID_PARAMETERS
            if (schedules.map { it.parameter } != catalog) return ProfileFailure.INVALID_PARAMETERS
            for (schedule in schedules) {
                val segments = schedule.segments
                if (segments.isEmpty() || segments.first().startMinute != 0 ||
                    segments.last().endMinute != ProfileCatalog.MINUTES_PER_DAY ||
                    segments.zipWithNext().any { (a, b) -> a.endMinute != b.startMinute }) {
                    return ProfileFailure.INVALID_SEGMENTS
                }
            }
            if (unit !is Setting.Declared && schedules.any { it.parameter.glucoseDependent && it.hasEnteredValue }) {
                return ProfileFailure.UNIT_REQUIRED
            }
            return null
        }
    }
}

sealed interface ProfileEdit {
    data class Changed(val content: ProfileContent) : ProfileEdit
    data class Rejected(val reason: ProfileFailure) : ProfileEdit
}
