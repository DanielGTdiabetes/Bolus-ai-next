package org.bolusai.profile

/** Synthetic values only. None of these numbers is a clinical recommendation or default. */
internal fun decimal(text: String) = CanonicalDecimal(text)
internal fun entered(text: String) = ProfileValue.Entered(decimal(text))
internal val mgdl: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MG_DL)
internal val mmol: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MMOL_L)
internal val madrid: Setting<ProfileTimeZone> = Setting.Declared(ProfileTimeZone("Europe/Madrid"))

internal fun content(
    unit: Setting<GlucoseUnit> = mgdl,
    zone: Setting<ProfileTimeZone> = madrid,
    ratio: ProfileValue = entered("10"),
    sensitivity: ProfileValue = entered("40"),
    target: ProfileValue = entered("110"),
) = ProfileContent(1, unit, zone, listOf(
    ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, ratio),
    ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, sensitivity),
    ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, target),
))

/** Mirrors the SQLite adapter: the shared policies decide after validating everything, this fake only appends. */
internal class MemoryProfileRepository : ClinicalProfileRepository {
    val versions = mutableListOf<ProfileVersion>()
    val events = mutableListOf<ConfirmationEvent>()
    var reads = 0
    var writes = 0
    var failNextSave = false
    /** The next confirmation insert fails before commit: nothing is kept. */
    var failNextCommit = false
    /** The next confirmation insert commits but the caller never receives the answer. */
    var loseNextResponse = false

    private fun record(): ProfileRecordRead = ProfileRecord.validate(versions.toList(), events.toList())

    override fun readVersions(): ProfileRead {
        reads++
        val record = record()
        // Like the adapter: events that cannot be proven fail the read; version chains are judged by the use case.
        return if (events.isNotEmpty() && record is ProfileRecordRead.Failed) ProfileRead.Failed(record.reason)
        else ProfileRead.Loaded(versions.toList())
    }

    override fun readRecord(): ProfileRecordRead { reads++; return record() }

    override fun save(write: ProfileWrite, createdAtEpochMs: Long, writer: String): ProfileSave {
        writes++
        if (failNextSave) { failNextSave = false; return ProfileSave.Failed(ProfileFailure.SAVE_FAILED) }
        // Never append to a history whose versions or events cannot be proven (ADR 0014, section 7.5).
        (record() as? ProfileRecordRead.Failed)?.let { return ProfileSave.Failed(it.reason) }
        val latest = versions.maxByOrNull { it.version }
        val decision = ProfileWritePolicy.evaluate(write, createdAtEpochMs, writer, latest,
            versions.firstOrNull { it.version == write.baseVersion + 1 },
            write.restoredFrom?.let { number -> versions.firstOrNull { it.version == number } })
        return when (decision) {
            is ProfileWriteDecision.Insert -> { versions.add(decision.version); ProfileSave.Saved(decision.version) }
            is ProfileWriteDecision.Existing -> ProfileSave.Saved(decision.version)
            is ProfileWriteDecision.Reject -> ProfileSave.Failed(decision.reason)
        }
    }

    override fun appendConfirmation(request: ConfirmationRequest, recordedAtEpochMs: Long, writer: String,
                                    zones: TimeZoneRules): ConfirmationWrite {
        writes++
        val record = when (val read = record()) {
            is ProfileRecordRead.Failed -> return ConfirmationWrite.Failed(read.reason)
            is ProfileRecordRead.Loaded -> read.record
        }
        return when (val decision = ConfirmationPolicy.evaluate(request, recordedAtEpochMs, writer, record, zones)) {
            is ConfirmationDecision.Reject -> ConfirmationWrite.Failed(decision.reason)
            is ConfirmationDecision.Replay -> ConfirmationWrite.Recorded(decision.event, true, record)
            is ConfirmationDecision.Insert -> {
                if (failNextCommit) { failNextCommit = false; return ConfirmationWrite.Failed(ProfileFailure.SAVE_FAILED) }
                events.add(decision.event)
                val committed = (record() as ProfileRecordRead.Loaded).record
                if (loseNextResponse) { loseNextResponse = false; return ConfirmationWrite.Failed(ProfileFailure.SAVE_FAILED) }
                ConfirmationWrite.Recorded(decision.event, false, committed)
            }
        }
    }
}

/** Synthetic operation identities; real ones come from the platform's random UUIDs. */
internal fun operationId(n: Int) = OperationId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))

internal class SequentialOperationIds(private var next: Int = 1) : OperationIds {
    override fun next(): OperationId = operationId(next++)
}

/** Platform zone rules that can retire an identifier, as a tz database update might (ADR 0014, E11). */
internal class MutableZones(vararg ids: String) : TimeZoneRules {
    val known = ids.toMutableSet()
    override fun exists(id: String): Boolean = id in known
}

internal class FixedClock(var now: Long = 1_790_000_000_000) : ProfileClock {
    override fun nowEpochMs(): Long = now++
}

internal val knownZones = TimeZoneRules { it in setOf("Europe/Madrid", "UTC", "America/New_York") }

internal fun profiles(repository: MemoryProfileRepository, clock: FixedClock = FixedClock()) =
    ClinicalProfiles(repository, clock, "test/synthetic", knownZones)

internal fun ClinicalProfiles.saved(editor: ProfileEditor): ProfileVersion =
    (save(editor) as ProfileSave.Saved).version

internal fun ClinicalProfiles.loaded(): ProfileHistory.Loaded = read() as ProfileHistory.Loaded

internal fun ProfileEditor.with(next: ProfileContent) = copy(content = next)

/** Sets the value of a schedule that is still a single full-day segment. */
internal fun ProfileContent.withDayValue(parameter: ProfileParameter, value: ProfileValue): ProfileEdit =
    edited(SegmentOperation.SetValue(parameter, SegmentRef(0, ProfileCatalog.MINUTES_PER_DAY), value))

internal fun segment(start: Int, end: Int, value: ProfileValue = ProfileValue.NotConfigured) = TimeSegment(start, end, value)

internal fun ProfileContent.withSchedule(parameter: ProfileParameter, vararg segments: TimeSegment) =
    copy(schedules = schedules.map { if (it.parameter == parameter) ParameterSchedule(parameter, segments.toList()) else it })

internal fun ProfileEdit.content(): ProfileContent = (this as ProfileEdit.Changed).content
