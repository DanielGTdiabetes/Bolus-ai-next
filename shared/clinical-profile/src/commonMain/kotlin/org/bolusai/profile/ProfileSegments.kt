package org.bolusai.profile

/**
 * The interval of a segment as the caller last observed it. Every operation names segments by interval, never by
 * position, so a repeated or late event on an interval that no longer exists changes nothing (ADR 0013, section 3.5).
 */
data class SegmentRef(val startMinute: Int, val endMinute: Int) {
    val key: String get() = "$startMinute-$endMinute"

    companion object {
        fun of(segment: TimeSegment) = SegmentRef(segment.startMinute, segment.endMinute)
    }
}

/** Local wall-clock times of day. Always 24 h, one-minute resolution, no rounding (ADR 0013, sections 3.6 and 4). */
object ProfileTimes {
    /** Technical usability bound for splitting only (T2). Never checked by the model, the policy or storage. */
    const val MAX_SEGMENTS_FOR_SPLIT = 48
    private val PATTERN = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$")

    /** `HH:MM`. The end of the day is shown as `24:00`, never as `00:00`. */
    fun format(minute: Int): String {
        require(minute in 0..ProfileCatalog.MINUTES_PER_DAY) { "profile.time.range" }
        val hours = minute / 60
        val minutes = minute % 60
        return "${if (hours < 10) "0" else ""}$hours:${if (minutes < 10) "0" else ""}$minutes"
    }

    /**
     * Accepts `H:MM` or `HH:MM` with hours 0..23. Rejects `24:00`, seconds, suffixes, AM/PM and any other spelling.
     * Whether the minute is usable as a boundary is decided by the operation, not here.
     */
    fun parse(input: String): TimeInput {
        val text = input.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        if (text.isEmpty()) return TimeInput.Blank
        val match = PATTERN.matchEntire(text) ?: return TimeInput.Invalid(ProfileFailure.INVALID_TIME)
        return TimeInput.Valid(match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt())
    }
}

sealed interface TimeInput {
    data object Blank : TimeInput
    data class Valid(val minute: Int) : TimeInput
    data class Invalid(val reason: ProfileFailure) : TimeInput
}

/**
 * Editing operations on one parameter's schedule. Three are structural and one changes a value. None of them can
 * leave a gap, an overlap or a disordered schedule, and none normalizes anything the user did not act on.
 */
sealed interface SegmentOperation {
    val parameter: ProfileParameter
    val structural: Boolean

    /** Creates two segments at an interior minute. Both keep the original value exactly. */
    data class Split(override val parameter: ProfileParameter, val segment: SegmentRef, val atMinute: Int) : SegmentOperation {
        override val structural get() = true
    }

    /** Joins [segment] with [next]. Allowed only when both values are exactly equal. */
    data class MergeWithNext(override val parameter: ProfileParameter, val segment: SegmentRef, val next: SegmentRef) :
        SegmentOperation {
        override val structural get() = true
    }

    /** Moves the boundary shared by [before] and [after]. Values do not move. */
    data class MoveBoundary(override val parameter: ProfileParameter, val before: SegmentRef, val after: SegmentRef,
                            val toMinute: Int) : SegmentOperation {
        override val structural get() = true
    }

    data class SetValue(override val parameter: ProfileParameter, val segment: SegmentRef, val value: ProfileValue) :
        SegmentOperation {
        override val structural get() = false
    }
}

/** Pure schedule edits. The resulting content is re-validated by [ProfileContent]'s own invariants. */
fun ProfileContent.edited(operation: SegmentOperation): ProfileEdit {
    val schedule = schedule(operation.parameter)
    val segments = schedule.segments
    fun indexOf(ref: SegmentRef) = segments.indexOfFirst { it.startMinute == ref.startMinute && it.endMinute == ref.endMinute }
    fun replaced(next: List<TimeSegment>) = ProfileEdit.Changed(copy(schedules = schedules.map {
        if (it.parameter == operation.parameter) ParameterSchedule(operation.parameter, next) else it
    }))

    return when (operation) {
        is SegmentOperation.Split -> {
            val index = indexOf(operation.segment)
            if (index < 0) return ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT)
            if (segments.size >= ProfileTimes.MAX_SEGMENTS_FOR_SPLIT) {
                return ProfileEdit.Rejected(ProfileFailure.SEGMENT_LIMIT_REACHED)
            }
            val target = segments[index]
            if (operation.atMinute <= target.startMinute || operation.atMinute >= target.endMinute) {
                return ProfileEdit.Rejected(ProfileFailure.SPLIT_OUT_OF_RANGE)
            }
            replaced(segments.subList(0, index) + listOf(
                TimeSegment(target.startMinute, operation.atMinute, target.value),
                TimeSegment(operation.atMinute, target.endMinute, target.value),
            ) + segments.subList(index + 1, segments.size))
        }
        is SegmentOperation.MergeWithNext -> {
            val index = indexOf(operation.segment)
            if (index < 0 || index + 1 >= segments.size || indexOf(operation.next) != index + 1) {
                return ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT)
            }
            val first = segments[index]
            val second = segments[index + 1]
            // A merge never discards a value: not configured and zero are different values.
            if (first.value != second.value) return ProfileEdit.Rejected(ProfileFailure.MERGE_VALUES_DIFFER)
            replaced(segments.subList(0, index) + TimeSegment(first.startMinute, second.endMinute, first.value) +
                segments.subList(index + 2, segments.size))
        }
        is SegmentOperation.MoveBoundary -> {
            val index = indexOf(operation.before)
            if (index < 0 || index + 1 >= segments.size || indexOf(operation.after) != index + 1) {
                return ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT)
            }
            val before = segments[index]
            val after = segments[index + 1]
            if (operation.toMinute <= before.startMinute || operation.toMinute >= after.endMinute) {
                return ProfileEdit.Rejected(ProfileFailure.BOUNDARY_OUT_OF_RANGE)
            }
            replaced(segments.subList(0, index) + listOf(
                TimeSegment(before.startMinute, operation.toMinute, before.value),
                TimeSegment(operation.toMinute, after.endMinute, after.value),
            ) + segments.subList(index + 2, segments.size))
        }
        is SegmentOperation.SetValue -> {
            val index = indexOf(operation.segment)
            if (index < 0) return ProfileEdit.Rejected(ProfileFailure.STALE_SEGMENT)
            if (operation.parameter.glucoseDependent && operation.value is ProfileValue.Entered &&
                glucoseUnit !is Setting.Declared) {
                return ProfileEdit.Rejected(ProfileFailure.UNIT_REQUIRED)
            }
            replaced(segments.mapIndexed { i, segment -> if (i == index) segment.copy(value = operation.value) else segment })
        }
    }
}

/** A merge the UI may offer: the next segment exists and holds exactly the same value. Never applied automatically. */
fun ParameterSchedule.canMergeWithNext(segment: SegmentRef): Boolean {
    val index = segments.indexOfFirst { it.startMinute == segment.startMinute && it.endMinute == segment.endMinute }
    return index >= 0 && index + 1 < segments.size && segments[index].value == segments[index + 1].value
}

val ParameterSchedule.canSplit: Boolean get() = segments.size < ProfileTimes.MAX_SEGMENTS_FOR_SPLIT
