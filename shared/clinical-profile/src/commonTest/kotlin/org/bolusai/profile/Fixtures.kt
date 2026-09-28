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

/** Mirrors the SQLite adapter: the shared policy decides, this fake only appends. */
internal class MemoryProfileRepository : ClinicalProfileRepository {
    val versions = mutableListOf<ProfileVersion>()
    var reads = 0
    var writes = 0
    var failNextSave = false

    override fun readVersions(): ProfileRead { reads++; return ProfileRead.Loaded(versions.toList()) }

    override fun save(write: ProfileWrite, createdAtEpochMs: Long, writer: String): ProfileSave {
        writes++
        if (failNextSave) { failNextSave = false; return ProfileSave.Failed(ProfileFailure.SAVE_FAILED) }
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
