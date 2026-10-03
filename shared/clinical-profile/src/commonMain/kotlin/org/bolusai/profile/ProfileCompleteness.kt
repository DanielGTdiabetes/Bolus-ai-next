package org.bolusai.profile

/**
 * Structural completeness of one saved version (ADR 0014, section 3). It reuses only approved structural rules and adds
 * no clinical range: an explicit `0` counts as configured, "not configured" never does, and the 48-segment limit of the
 * editor is not a condition here. Coverage, catalog and canonical decimals are already guaranteed by [ProfileContent].
 */
sealed interface ProfileCompleteness {
    data object Complete : ProfileCompleteness

    /** [gaps] is never empty and always complete, in a deterministic order (see [ProfileContent.completeness]). */
    data class Incomplete(val gaps: List<CompletenessGap>) : ProfileCompleteness {
        init { require(gaps.isNotEmpty()) }
    }
}

sealed interface CompletenessGap {
    val code: String

    data object UnitNotDeclared : CompletenessGap { override val code = "unit_not_declared" }
    data object TimeZoneNotDeclared : CompletenessGap { override val code = "time_zone_not_declared" }

    /** Declared and well formed, but unknown to the platform time zone database at the moment of evaluation. */
    data class TimeZoneUnrecognized(val id: String) : CompletenessGap { override val code = "time_zone_unrecognized" }

    /** One segment [startMinute, endMinute) of [parameter] without a value. */
    data class ValueNotConfigured(val parameter: ProfileParameter, val startMinute: Int, val endMinute: Int) : CompletenessGap {
        override val code = "value_not_configured"
    }
}

/**
 * Pure evaluation against the platform rules passed in. Order: unit, time zone, then every unconfigured segment in
 * catalog order and by start minute. Every gap is reported, not only the first.
 */
fun ProfileContent.completeness(zones: TimeZoneRules): ProfileCompleteness {
    val gaps = buildList {
        if (glucoseUnit !is Setting.Declared) add(CompletenessGap.UnitNotDeclared)
        when (val zone = timeZone) {
            Setting.NotConfigured -> add(CompletenessGap.TimeZoneNotDeclared)
            is Setting.Declared -> if (!zones.exists(zone.value.id)) add(CompletenessGap.TimeZoneUnrecognized(zone.value.id))
        }
        schedules.forEach { schedule ->
            schedule.segments.sortedBy { it.startMinute }.forEach { segment ->
                if (segment.value is ProfileValue.NotConfigured) {
                    add(CompletenessGap.ValueNotConfigured(schedule.parameter, segment.startMinute, segment.endMinute))
                }
            }
        }
    }
    return if (gaps.isEmpty()) ProfileCompleteness.Complete else ProfileCompleteness.Incomplete(gaps)
}

fun ProfileVersion.completeness(zones: TimeZoneRules): ProfileCompleteness = content.completeness(zones)
