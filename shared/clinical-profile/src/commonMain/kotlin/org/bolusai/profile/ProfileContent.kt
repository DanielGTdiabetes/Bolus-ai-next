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

/** Content schema catalogs. A new parameter is a new content schema, never a change to schema 1. */
object ProfileCatalog {
    const val CURRENT_SCHEMA = 1
    const val MINUTES_PER_DAY = 1440
    private val schema1 = listOf(ProfileParameter.CARB_RATIO, ProfileParameter.INSULIN_SENSITIVITY,
        ProfileParameter.GLUCOSE_TARGET)

    fun parameters(schemaVersion: Int): List<ProfileParameter>? = if (schemaVersion == 1) schema1 else null
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
 * No clinical range is checked.
 */
data class ProfileContent(
    val schemaVersion: Int,
    val glucoseUnit: Setting<GlucoseUnit>,
    val timeZone: Setting<ProfileTimeZone>,
    val schedules: List<ParameterSchedule>,
) {
    init {
        val problem = problem(schemaVersion, glucoseUnit, schedules)
        require(problem == null) { problem?.code ?: "" }
    }

    fun schedule(parameter: ProfileParameter): ParameterSchedule = schedules.first { it.parameter == parameter }

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
