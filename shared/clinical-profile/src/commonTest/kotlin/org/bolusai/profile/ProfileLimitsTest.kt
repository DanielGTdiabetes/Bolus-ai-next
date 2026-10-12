package org.bolusai.profile

import kotlin.test.*

/** Content schema 2: profile-wide rounding step, maximum bolus and maximum correction (ADR 0025). Synthetic values. */
class ProfileLimitsTest {
    private fun limited(step: ProfileValue = entered("0.5"), bolus: ProfileValue = entered("10"),
                        correction: ProfileValue = entered("4")) =
        content().withLimit(ProfileLimit.ROUND_STEP, step).withLimit(ProfileLimit.MAX_BOLUS, bolus)
            .withLimit(ProfileLimit.MAX_CORRECTION, correction)

    @Test fun schemaOneHasNoLimitsAndKeepsItsTextAndFingerprint() {
        val old = content()
        assertEquals(1, old.schemaVersion)
        ProfileLimit.entries.forEach { assertNull(old.limit(it), it.code) }
        // Same text as before schema 2 existed (also pinned in ProfileFingerprintTest), so stored fingerprints still match.
        assertEquals("""
            bolus-ai-next/clinical-profile/content
            schema=1
            glucose_unit=mg/dL
            time_zone=Europe/Madrid
            parameter=carb_ratio
            segment=0-1440:10
            parameter=glucose_target
            segment=0-1440:110
            parameter=insulin_sensitivity
            segment=0-1440:40

        """.trimIndent(), ProfileCodec.encode(old))
    }

    // Since ADR 0027 new profiles start in schema 3, which keeps every schema 2 limit.
    @Test fun newProfilesUseTheCurrentSchemaWithEveryLimitExplicitlyNotConfigured() {
        val empty = ProfileContent.empty()
        assertEquals(ProfileCatalog.CURRENT_SCHEMA, empty.schemaVersion)
        ProfileLimit.entries.forEach { assertEquals(ProfileValue.NotConfigured, empty.limit(it), it.code) }
        assertTrue(empty.completeness(knownZones) is ProfileCompleteness.Incomplete)
    }

    @Test fun canonicalTextOfSchemaTwoIsStableStrictAndRoundTrips() {
        val profile = limited(correction = ProfileValue.NotConfigured)
        val expected = """
            bolus-ai-next/clinical-profile/content
            schema=2
            glucose_unit=mg/dL
            time_zone=Europe/Madrid
            limit=max_bolus:10
            limit=max_correction:~
            limit=round_step:0.5
            parameter=carb_ratio
            segment=0-1440:10
            parameter=glucose_target
            segment=0-1440:110
            parameter=insulin_sensitivity
            segment=0-1440:40

        """.trimIndent()
        assertEquals(expected, ProfileCodec.encode(profile))
        assertEquals(profile, ProfileCodec.decode(expected))
        listOf(
            expected.replace("limit=max_correction:~\n", ""),
            expected.replace("limit=max_correction:~\n", "limit=max_correction:~\nlimit=max_correction:~\n"),
            expected.replace("limit=max_bolus:10", "limit=max_iob:10"),
            expected.replace("limit=max_bolus:10", "limit=max_bolus:10.0"),
            expected.replace("limit=max_bolus:10", "limit=max_bolus"),
            expected.replace("schema=2", "schema=1"),
            ProfileCodec.encode(content()).replace("time_zone=Europe/Madrid\n", "time_zone=Europe/Madrid\nlimit=round_step:1\n"),
        ).forEach { assertNull(ProfileCodec.decode(it), it) }
    }

    @Test fun fingerprintSeesEveryLimitAndAbsenceDiffersFromZero() {
        val base = limited()
        val sha = ProfileCodec.sha256(base)
        listOf(
            limited(step = entered("0.1")), limited(bolus = entered("12")), limited(correction = entered("5")),
            limited(step = ProfileValue.NotConfigured), limited(bolus = entered("0")), content(),
        ).forEach { assertNotEquals(sha, ProfileCodec.sha256(it), it.toString()) }
        assertNotEquals(ProfileCodec.sha256(limited(bolus = entered("0"))),
            ProfileCodec.sha256(limited(bolus = ProfileValue.NotConfigured)))
    }

    @Test fun constructionRejectsLimitsOutsideTheSchema() {
        val schedules = content().schedules
        assertFailsWith<IllegalArgumentException> {
            ProfileContent(1, mgdl, madrid, schedules, mapOf(ProfileLimit.ROUND_STEP to entered("1")))
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileContent(2, mgdl, madrid, schedules, mapOf(ProfileLimit.ROUND_STEP to entered("1")))
        }
    }

    @Test fun enteringALimitUpgradesSchemaOneAndClearingOneThereChangesNothing() {
        val old = content()
        assertSame(old, old.withLimit(ProfileLimit.MAX_BOLUS, ProfileValue.NotConfigured))
        val upgraded = old.withLimit(ProfileLimit.MAX_BOLUS, entered("10"))
        assertEquals(2, upgraded.schemaVersion)
        assertEquals(entered("10"), upgraded.limit(ProfileLimit.MAX_BOLUS))
        // The other limits start not configured: nothing is copied or assumed.
        assertEquals(ProfileValue.NotConfigured, upgraded.limit(ProfileLimit.ROUND_STEP))
        assertEquals(ProfileValue.NotConfigured, upgraded.limit(ProfileLimit.MAX_CORRECTION))
        assertEquals(old.schedules, upgraded.schedules)
        // Unit changes and segment edits keep the limits.
        assertEquals(upgraded.limits, upgraded.withGlucoseUnit(mmol).limits)
        assertEquals(upgraded.limits, upgraded.withDayValue(ProfileParameter.CARB_RATIO, entered("12")).content().limits)
    }

    @Test fun editorGoesBackToSchemaOneWhenALimitIsEnteredAndCleared() {
        val start = content()
        val editor = ProfileEditor(1, start, start, null)
        val typed = editor.withLimit(ProfileLimit.ROUND_STEP, entered("0.5"))
        assertTrue(typed.changed)
        val cleared = typed.withLimit(ProfileLimit.ROUND_STEP, ProfileValue.NotConfigured)
        assertEquals(start, cleared.content)
        assertFalse(cleared.changed)
        // From a current-schema start an explicit "not configured" stays in that schema.
        val fresh = ProfileContent.empty()
        val newEditor = ProfileEditor(0, fresh, fresh, null)
        assertEquals(ProfileCatalog.CURRENT_SCHEMA, newEditor.withLimit(ProfileLimit.MAX_BOLUS, entered("8"))
            .withLimit(ProfileLimit.MAX_BOLUS, ProfileValue.NotConfigured).content.schemaVersion)
    }

    @Test fun limitsAreSavedOnceAndCarriedByEveryLaterEditWithoutBeingRetyped() {
        val repository = MemoryProfileRepository()
        val useCases = profiles(repository)
        val v1 = useCases.saved(ProfileEditor(0, ProfileContent.empty(), content(), null))
        assertEquals(1, v1.content.schemaVersion)
        val v2 = useCases.saved(useCases.edit(useCases.loaded()).withLimit(ProfileLimit.ROUND_STEP, entered("0.5"))
            .withLimit(ProfileLimit.MAX_BOLUS, entered("10")).withLimit(ProfileLimit.MAX_CORRECTION, entered("4")))
        assertEquals(2, v2.content.schemaVersion)
        // The earlier version is untouched and keeps its limits unknown.
        assertEquals(v1, useCases.loaded().version(1))
        ProfileLimit.entries.forEach { assertNull(useCases.loaded().version(1)!!.content.limit(it)) }
        val editor = useCases.edit(useCases.loaded())
        val v3 = useCases.saved(editor.with(editor.content.withDayValue(ProfileParameter.CARB_RATIO, entered("12")).content()))
        assertEquals(v2.content.limits, v3.content.limits)
    }

    @Test fun manualDowngradeIsRejectedButAnExactRestorationKeepsTheOldSchema() {
        val repository = MemoryProfileRepository()
        val useCases = profiles(repository)
        useCases.saved(ProfileEditor(0, ProfileContent.empty(), content(), null))
        val v2 = useCases.saved(useCases.edit(useCases.loaded()).withLimit(ProfileLimit.MAX_BOLUS, entered("10")))
        val downgrade = ProfileWrite(2, content(ratio = entered("11")), ProfileOrigin.MANUAL, null)
        assertEquals(ProfileFailure.SCHEMA_DOWNGRADE,
            (repository.save(downgrade, 1, "test/synthetic") as ProfileSave.Failed).reason)
        val restore = useCases.restore(useCases.loaded(), 1) as ProfileRestore.Ready
        val v3 = useCases.saved(restore.editor)
        assertEquals(ProfileOrigin.RESTORED, v3.origin)
        assertEquals(1, v3.content.schemaVersion)
        // A manual edit that starts from that restored schema 1 version may stay in schema 1.
        val editor = useCases.edit(useCases.loaded())
        val v4 = useCases.saved(editor.with(editor.content.withDayValue(ProfileParameter.CARB_RATIO, entered("11")).content()))
        assertEquals(1, v4.content.schemaVersion)
        assertEquals(entered("10"), useCases.loaded().version(v2.version)!!.content.limit(ProfileLimit.MAX_BOLUS))
    }

    @Test fun historyWithAManualDowngradeIsRejectedOnRead() {
        val v1 = ProfileContent.empty().withLimit(ProfileLimit.MAX_BOLUS, entered("10"))
        val v2 = content()
        val versions = listOf(
            ProfileVersion(2, v2, ProfileCodec.sha256(v2), ProfileOrigin.MANUAL, null, 2, "w"),
            ProfileVersion(1, v1, ProfileCodec.sha256(v1), ProfileOrigin.MANUAL, null, 1, "w"),
        )
        assertFailsWith<IllegalArgumentException> { ProfileHistory.Loaded(versions) }
    }

    @Test fun limitsNeverBlockCompletenessOrConfirmation() {
        val repository = MemoryProfileRepository()
        val useCases = profiles(repository)
        val fresh = ProfileContent.empty()
        val complete = fresh.copy(glucoseUnit = mgdl, timeZone = madrid, schedules = content().schedules)
        assertEquals(ProfileCompleteness.Complete, complete.completeness(knownZones))
        useCases.saved(ProfileEditor(0, fresh, complete, null))
        val state = useCases.readState() as ProfileGateState.Evaluated
        assertTrue(state.canConfirm)
        val outcome = useCases.record(useCases.confirmRequest(state, operationId(1)))
        assertTrue(outcome is ConfirmationOutcome.Recorded)
    }
}
