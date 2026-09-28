package org.bolusai.profile

sealed interface ProfileRead {
    /** Every stored version, in any order. An empty list means no profile was ever saved. */
    data class Loaded(val versions: List<ProfileVersion>) : ProfileRead
    data class Failed(val reason: ProfileFailure) : ProfileRead
}

sealed interface ProfileSave {
    data class Saved(val version: ProfileVersion) : ProfileSave
    data class Failed(val reason: ProfileFailure) : ProfileSave
}

/** Local persistence port. Implementations append only and evaluate [ProfileWritePolicy] inside one transaction. */
interface ClinicalProfileRepository {
    /** One consistent read of every version. */
    fun readVersions(): ProfileRead
    /** Returns only after commit; identical retries are idempotent; never updates or deletes a version. */
    fun save(write: ProfileWrite, createdAtEpochMs: Long, writer: String): ProfileSave
}

fun interface ProfileClock { fun nowEpochMs(): Long }

/** Platform check that a well-formed IANA identifier exists in the local time zone database. */
fun interface TimeZoneRules { fun exists(id: String): Boolean }

/**
 * Stored history, newest first and contiguous down to version 1. Every transition is re-validated on read,
 * so a tampered row fails the whole history instead of showing a partial or reinterpreted profile.
 */
sealed interface ProfileHistory {
    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false
    val blockCode: String

    data class Loaded(val versions: List<ProfileVersion>) : ProfileHistory {
        init {
            require(versions.isNotEmpty())
            require(versions.first().version == versions.size.toLong() && versions.last().version == 1L)
            require(versions.zipWithNext().all { (newer, older) -> newer.version - 1 == older.version })
            val byNumber = versions.associateBy { it.version }
            versions.forEach { version ->
                require(ProfileRules.transitionProblem(version.content, version.contentSha256, version.origin,
                    version.restoredFrom, byNumber[version.version - 1],
                    version.restoredFrom?.let { byNumber[it] }) == null) { ProfileFailure.INVALID_RECORD.code }
            }
            require(versions.zipWithNext().all { (newer, older) -> newer.contentSha256 != older.contentSha256 })
        }
        val latest: ProfileVersion get() = versions.first()
        fun version(number: Long): ProfileVersion? = versions.firstOrNull { it.version == number }
        override val blockCode: String get() = ProfileVersion.NOT_APPROVED_CODE
    }

    /** The store answered successfully and no version exists. */
    data object Missing : ProfileHistory {
        override val blockCode = "profile.history.missing"
    }

    /** The store could not prove the history; never presented as missing. */
    data class Failed(val reason: ProfileFailure) : ProfileHistory {
        override val blockCode: String get() = reason.code
    }
}

/**
 * Editor session. [start] is the content the session started from (latest version, restored version or an empty
 * profile). The origin is derived, never chosen by the caller.
 */
data class ProfileEditor(
    val baseVersion: Long,
    val start: ProfileContent,
    val content: ProfileContent,
    val restoredFrom: Long?,
) {
    init {
        require(baseVersion >= 0)
        require(restoredFrom == null || restoredFrom in 1 until baseVersion)
    }

    val origin: ProfileOrigin
        get() = if (restoredFrom != null && content == start) ProfileOrigin.RESTORED else ProfileOrigin.MANUAL

    /** Glucose-dependent values stay locked after a unit change until that change is saved as its own version. */
    val glucoseDependentValuesLocked: Boolean
        get() = start.glucoseUnit is Setting.Declared && content.glucoseUnit != start.glucoseUnit

    /** While a unit change is pending, glucose-dependent schedules are locked in values and structure (ADR 0013, §6). */
    fun scheduleLocked(parameter: ProfileParameter): Boolean = parameter.glucoseDependent && glucoseDependentValuesLocked

    /**
     * Applies one editing operation. A locked schedule accepts no structural change and no entered value; clearing a
     * value is harmless and allowed. Nothing is written.
     */
    fun edited(operation: SegmentOperation): ProfileEditorEdit {
        if (scheduleLocked(operation.parameter)) {
            if (operation.structural) return ProfileEditorEdit.Rejected(ProfileFailure.SCHEDULE_LOCKED)
            if ((operation as SegmentOperation.SetValue).value is ProfileValue.Entered) {
                return ProfileEditorEdit.Rejected(ProfileFailure.UNIT_CHANGE_WITH_VALUES)
            }
        }
        return when (val edit = content.edited(operation)) {
            is ProfileEdit.Rejected -> ProfileEditorEdit.Rejected(edit.reason)
            is ProfileEdit.Changed -> ProfileEditorEdit.Changed(copy(content = edit.content))
        }
    }

    /**
     * Editor-only indicator (ADR 0013, §8): the segment's interval does not exist in [start] for the same parameter, or
     * exists with another value. Derived from [start] and [content]; never stored, fingerprinted or used for origin.
     */
    fun isModified(parameter: ProfileParameter, segment: TimeSegment): Boolean =
        start.schedule(parameter).segments.none { it == segment }

    val nextVersion: Long get() = baseVersion + 1
    val changed: Boolean get() = content != start
    fun toWrite() = ProfileWrite(baseVersion, content, origin, restoredFrom)
}

sealed interface ProfileEditorEdit {
    data class Changed(val editor: ProfileEditor) : ProfileEditorEdit
    data class Rejected(val reason: ProfileFailure) : ProfileEditorEdit
}

sealed interface ProfileRestore {
    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false
    data class Ready(val editor: ProfileEditor, val source: ProfileVersion, val latest: ProfileVersion) : ProfileRestore
    data class Rejected(val reason: ProfileFailure) : ProfileRestore
}

/** Use cases. No clinical interpretation, no calculation and no network. */
class ClinicalProfiles(
    private val repository: ClinicalProfileRepository,
    private val clock: ProfileClock,
    private val writer: String,
    private val zones: TimeZoneRules,
) {
    init { require(ProfileVersion.isValidWriter(writer)) }

    fun read(): ProfileHistory = when (val result = repository.readVersions()) {
        is ProfileRead.Failed -> ProfileHistory.Failed(result.reason)
        is ProfileRead.Loaded -> if (result.versions.isEmpty()) ProfileHistory.Missing else try {
            ProfileHistory.Loaded(result.versions.sortedByDescending { it.version })
        } catch (_: IllegalArgumentException) {
            ProfileHistory.Failed(ProfileFailure.INVALID_RECORD)
        }
    }

    fun newProfile(): ProfileEditor = ProfileContent.empty().let { ProfileEditor(0, it, it, null) }

    fun edit(history: ProfileHistory.Loaded): ProfileEditor =
        history.latest.let { ProfileEditor(it.version, it.content, it.content, null) }

    /** Pure: loads an older version's exact content as a new editor based on the latest version. Writes nothing. */
    fun restore(history: ProfileHistory.Loaded, version: Long): ProfileRestore {
        val latest = history.latest
        val source = history.version(version)
        if (source == null || version >= latest.version) return ProfileRestore.Rejected(ProfileFailure.INVALID_RECORD)
        if (source.contentSha256 == latest.contentSha256) return ProfileRestore.Rejected(ProfileFailure.UNCHANGED)
        return ProfileRestore.Ready(ProfileEditor(latest.version, source.content, source.content, version), source, latest)
    }

    fun parseTimeZone(input: String): TimeZoneInput {
        val text = input.trim()
        if (text.isEmpty()) return TimeZoneInput.Valid(Setting.NotConfigured)
        if (!ProfileTimeZone.isWellFormed(text) || !zones.exists(text)) return TimeZoneInput.Invalid(ProfileFailure.INVALID_TIME_ZONE)
        return TimeZoneInput.Valid(Setting.Declared(ProfileTimeZone(text)))
    }

    fun save(editor: ProfileEditor): ProfileSave {
        val zone = editor.content.timeZone
        if (zone is Setting.Declared && !zones.exists(zone.value.id)) return ProfileSave.Failed(ProfileFailure.INVALID_TIME_ZONE)
        if (editor.glucoseDependentValuesLocked && editor.content.hasGlucoseDependentValues) {
            return ProfileSave.Failed(ProfileFailure.UNIT_CHANGE_WITH_VALUES)
        }
        return repository.save(editor.toWrite(), clock.nowEpochMs(), writer)
    }
}

sealed interface TimeZoneInput {
    data class Valid(val zone: Setting<ProfileTimeZone>) : TimeZoneInput
    data class Invalid(val reason: ProfileFailure) : TimeZoneInput
}
