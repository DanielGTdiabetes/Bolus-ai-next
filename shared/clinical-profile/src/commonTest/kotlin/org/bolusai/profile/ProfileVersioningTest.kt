package org.bolusai.profile

import kotlin.test.*

class ProfileVersioningTest {
    private val repository = MemoryProfileRepository()
    private val clock = FixedClock(1_000)
    private val profiles = profiles(repository, clock)

    private fun edit(change: (ProfileContent) -> ProfileContent) =
        profiles.edit(profiles.loaded()).let { it.with(change(it.content)) }

    @Test fun emptyStoreIsMissingAndFailuresAreNeverMissing() {
        assertEquals(ProfileHistory.Missing, profiles.read())
        val failing = object : ClinicalProfileRepository {
            override fun readVersions() = ProfileRead.Failed(ProfileFailure.CORRUPT_STORAGE)
            override fun readRecord() = ProfileRecordRead.Failed(ProfileFailure.CORRUPT_STORAGE)
            override fun save(write: ProfileWrite, createdAtEpochMs: Long, writer: String) = error("unused")
            override fun appendConfirmation(request: ConfirmationRequest, recordedAtEpochMs: Long, writer: String,
                                            zones: TimeZoneRules) = error("unused")
        }
        val history = ClinicalProfiles(failing, clock, "w", knownZones).read()
        assertEquals(ProfileHistory.Failed(ProfileFailure.CORRUPT_STORAGE), history)
        assertFalse(history.allowsCalculation)
        assertFalse(history.allowsTreatment)
    }

    @Test fun savingAppendsVersionsWithAuditMetadataAndNeverUnlocksCalculation() {
        val v1 = profiles.saved(profiles.newProfile().with(content(ratio = entered("0"))))
        assertEquals(1, v1.version)
        assertEquals(ProfileOrigin.MANUAL, v1.origin)
        assertNull(v1.restoredFrom)
        assertEquals(1_000, v1.createdAtEpochMs)
        assertEquals("test/synthetic", v1.writer)
        val v2 = profiles.saved(edit { content(ratio = ProfileValue.NotConfigured) })
        assertEquals(2, v2.version)
        val history = profiles.loaded()
        assertEquals(listOf(v2, v1), history.versions)
        assertEquals(entered("0"), history.version(1)!!.content.schedule(ProfileParameter.CARB_RATIO).segments.single().value)
        assertEquals(ProfileValue.NotConfigured, history.latest.content.schedule(ProfileParameter.CARB_RATIO).segments.single().value)
        assertFalse(history.allowsCalculation || history.allowsTreatment || v1.allowsCalculation || v1.allowsTreatment)
        assertEquals("profile.not_approved_for_calculation", history.blockCode)
    }

    @Test fun unchangedContentIsRejected() {
        profiles.saved(profiles.newProfile().with(content()))
        assertEquals(ProfileSave.Failed(ProfileFailure.UNCHANGED), profiles.save(profiles.edit(profiles.loaded())))
        assertEquals(ProfileSave.Failed(ProfileFailure.UNCHANGED),
            profiles.save(profiles.newProfile().with(content()).copy(baseVersion = 1)))
        assertEquals(1, repository.versions.size)
    }

    @Test fun staleEditorIsAConflictAndNothingIsOverwritten() {
        profiles.saved(profiles.newProfile().with(content()))
        val first = edit { content(ratio = entered("11")) }
        val second = edit { content(ratio = entered("12")) }
        val v2 = profiles.saved(second)
        assertEquals(ProfileSave.Failed(ProfileFailure.CONFLICT), profiles.save(first))
        assertEquals(ProfileSave.Failed(ProfileFailure.CONFLICT), profiles.save(profiles.newProfile().with(content(ratio = entered("13")))))
        assertEquals(v2, profiles.loaded().latest)
        assertEquals(2, repository.versions.size)
    }

    @Test fun identicalRetryReturnsTheCommittedVersionEvenAfterLaterVersions() {
        profiles.saved(profiles.newProfile().with(content()))
        val editor = edit { content(ratio = entered("11")) }
        val v2 = profiles.saved(editor)
        clock.now = 99_999
        assertEquals(ProfileSave.Saved(v2), profiles.save(editor))
        val v3 = profiles.saved(edit { content(ratio = entered("12")) })
        assertEquals(ProfileSave.Saved(v2), profiles.save(editor))
        assertEquals(listOf(v3.version, v2.version, 1L), profiles.loaded().versions.map { it.version })
    }

    @Test fun failedSaveKeepsTheEditorRetryable() {
        profiles.saved(profiles.newProfile().with(content()))
        val editor = edit { content(target = entered("100")) }
        repository.failNextSave = true
        assertEquals(ProfileSave.Failed(ProfileFailure.SAVE_FAILED), profiles.save(editor))
        assertEquals(1, repository.versions.size)
        assertEquals(2, profiles.saved(editor).version)
    }

    @Test fun reservedSystemProposalOriginIsRejected() {
        profiles.saved(profiles.newProfile().with(content()))
        val write = ProfileWrite(1, content(ratio = entered("11")), ProfileOrigin.SYSTEM_PROPOSAL_ACCEPTED, null)
        assertEquals(ProfileSave.Failed(ProfileFailure.ORIGIN_NOT_ENABLED), repository.save(write, 1, "w"))
        assertEquals(1, repository.versions.size)
        ProfileOrigin.entries.forEach { assertTrue(it.code.isNotBlank()) }
    }

    @Test fun invalidWriterOrBaseIsRejected() {
        assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD),
            repository.save(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), 1, ""))
        assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD),
            repository.save(ProfileWrite(-1, content(), ProfileOrigin.MANUAL, null), 1, "w"))
        assertFailsWith<IllegalArgumentException> { ClinicalProfiles(repository, clock, "", knownZones) }
    }

    @Test fun unknownTimeZoneIsNotSaved() {
        val editor = profiles.newProfile().with(content(zone = Setting.Declared(ProfileTimeZone("Mars/Olympus"))))
        assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_TIME_ZONE), profiles.save(editor))
        assertEquals(0, repository.writes)
    }

    @Test fun restoreIsPureExactAndCreatesANewVersion() {
        val v1 = profiles.saved(profiles.newProfile().with(content(ratio = entered("0"), sensitivity = ProfileValue.NotConfigured)))
        val v2 = profiles.saved(edit { content(ratio = entered("12")) })
        val reads = repository.reads
        val ready = profiles.restore(profiles.loaded(), 1) as ProfileRestore.Ready
        assertEquals(reads + 1, repository.reads)
        assertEquals(0, repository.writes - 2)
        assertEquals(v1.content, ready.editor.content)
        assertEquals(2, ready.editor.baseVersion)
        assertEquals(1L, ready.editor.restoredFrom)
        assertEquals(ProfileOrigin.RESTORED, ready.editor.origin)
        assertFalse(ready.allowsCalculation || ready.allowsTreatment)
        val v3 = profiles.saved(ready.editor)
        assertEquals(ProfileOrigin.RESTORED, v3.origin)
        assertEquals(1L, v3.restoredFrom)
        assertEquals(v1.contentSha256, v3.contentSha256)
        assertEquals(listOf(v3, v2, v1), profiles.loaded().versions)
        assertEquals(ProfileSave.Saved(v3), profiles.save(ready.editor))
    }

    @Test fun editingARestoredEditorBecomesManualAndUndoingReturnsToRestored() {
        profiles.saved(profiles.newProfile().with(content()))
        profiles.saved(edit { content(ratio = entered("12")) })
        val ready = (profiles.restore(profiles.loaded(), 1) as ProfileRestore.Ready).editor
        val edited = ready.with(content(ratio = entered("13")))
        assertEquals(ProfileOrigin.MANUAL, edited.origin)
        assertEquals(1L, edited.restoredFrom)
        assertEquals(ProfileOrigin.RESTORED, edited.with(ready.start).origin)
        val v3 = profiles.saved(edited)
        assertEquals(ProfileOrigin.MANUAL, v3.origin)
        assertEquals(1L, v3.restoredFrom)
    }

    @Test fun restoreRejectsLatestMissingAndIdenticalVersions() {
        profiles.saved(profiles.newProfile().with(content()))
        profiles.saved(edit { content(ratio = entered("12")) })
        profiles.saved(edit { content() })
        val history = profiles.loaded()
        assertEquals(ProfileRestore.Rejected(ProfileFailure.INVALID_RECORD), profiles.restore(history, 3))
        assertEquals(ProfileRestore.Rejected(ProfileFailure.INVALID_RECORD), profiles.restore(history, 9))
        assertEquals(ProfileRestore.Rejected(ProfileFailure.INVALID_RECORD), profiles.restore(history, 0))
        // Version 1 already equals the latest content.
        assertEquals(ProfileRestore.Rejected(ProfileFailure.UNCHANGED), profiles.restore(history, 1))
    }

    @Test fun forgedProvenanceIsRejectedByThePolicy() {
        profiles.saved(profiles.newProfile().with(content()))
        profiles.saved(edit { content(ratio = entered("12")) })
        val claimsRestored = ProfileWrite(2, content(ratio = entered("13")), ProfileOrigin.RESTORED, 1)
        assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_ORIGIN), repository.save(claimsRestored, 1, "w"))
        val hidesRestore = ProfileWrite(2, content(), ProfileOrigin.MANUAL, 1)
        assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_ORIGIN), repository.save(hidesRestore, 1, "w"))
        val restoresLatest = ProfileWrite(2, content(ratio = entered("12")), ProfileOrigin.RESTORED, 2)
        assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD), repository.save(restoresLatest, 1, "w"))
        val restoredWithoutSource = ProfileWrite(2, content(), ProfileOrigin.RESTORED, null)
        assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_ORIGIN), repository.save(restoredWithoutSource, 1, "w"))
        assertEquals(2, repository.versions.size)
    }

    @Test fun historyRequiresContiguousConsistentVersions() {
        val v1 = profiles.saved(profiles.newProfile().with(content()))
        val v2 = profiles.saved(edit { content(ratio = entered("12")) })
        val cases = listOf(
            listOf(v2),
            listOf(v1, v2, v2),
            listOf(v1, v2.copy(version = 3)),
            listOf(v1, ProfileVersion(2, v1.content, v1.contentSha256, ProfileOrigin.MANUAL, null, 1, "w")),
        )
        cases.forEach { versions ->
            repository.versions.clear(); repository.versions.addAll(versions)
            assertEquals(ProfileHistory.Failed(ProfileFailure.INVALID_RECORD), profiles.read(), versions.toString())
        }
        repository.versions.clear(); repository.versions.addAll(listOf(v2, v1))
        assertEquals(listOf(v2, v1), profiles.loaded().versions)
    }

    @Test fun editorStateDoesNotAcceptImpossibleProvenance() {
        assertFailsWith<IllegalArgumentException> { ProfileEditor(1, content(), content(), 1) }
        assertFailsWith<IllegalArgumentException> { ProfileEditor(-1, content(), content(), null) }
        assertEquals(1, profiles.newProfile().nextVersion)
        assertFalse(profiles.newProfile().changed)
    }
}
