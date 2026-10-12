package org.bolusai.profile

import kotlin.test.*

/** Content schema 3: insulin curve, DIA and peak as profile-wide declarations without defaults (ADR 0027). Synthetic. */
class ProfileInsulinActionTest {
    private fun limitsOnly() = content().withLimit(ProfileLimit.ROUND_STEP, entered("0.5"))

    private fun action(curve: Setting<InsulinCurveCode> = Setting.Declared(InsulinCurveCode.WALSH),
                       dia: ProfileValue = entered("4.5"), peak: ProfileValue = entered("75")) =
        limitsOnly().withInsulinCurve(curve).withLimit(ProfileLimit.INSULIN_DIA, dia)
            .withLimit(ProfileLimit.INSULIN_PEAK, peak)

    @Test fun earlierSchemasKeepTheInsulinActionUnknownAndTheirTextUnchanged() {
        val one = content()
        val two = limitsOnly()
        assertEquals(2, two.schemaVersion)
        for (old in listOf(one, two)) {
            assertNull(old.insulinCurve)
            assertNull(old.limit(ProfileLimit.INSULIN_DIA))
            assertNull(old.limit(ProfileLimit.INSULIN_PEAK))
            listOf("insulin_curve", "insulin_dia", "insulin_peak").forEach {
                assertFalse(it in ProfileCodec.encode(old), "${old.schemaVersion}:$it")
            }
        }
        // Schema 2 text exactly as pinned by ProfileLimitsTest before schema 3 existed.
        assertEquals(ProfileCodec.encode(two), """
            bolus-ai-next/clinical-profile/content
            schema=2
            glucose_unit=mg/dL
            time_zone=Europe/Madrid
            limit=max_bolus:~
            limit=max_correction:~
            limit=round_step:0.5
            parameter=carb_ratio
            segment=0-1440:10
            parameter=glucose_target
            segment=0-1440:110
            parameter=insulin_sensitivity
            segment=0-1440:40

        """.trimIndent())
    }

    @Test fun newProfilesStartWithTheActionExplicitlyNotConfigured() {
        val empty = ProfileContent.empty()
        assertEquals(3, empty.schemaVersion)
        assertEquals(Setting.NotConfigured, empty.insulinCurve)
        assertEquals(ProfileValue.NotConfigured, empty.limit(ProfileLimit.INSULIN_DIA))
        assertEquals(ProfileValue.NotConfigured, empty.limit(ProfileLimit.INSULIN_PEAK))
    }

    @Test fun canonicalTextOfSchemaThreeIsStableStrictAndRoundTrips() {
        val profile = action(peak = ProfileValue.NotConfigured)
        val expected = """
            bolus-ai-next/clinical-profile/content
            schema=3
            glucose_unit=mg/dL
            time_zone=Europe/Madrid
            limit=insulin_dia:4.5
            limit=insulin_peak:~
            limit=max_bolus:~
            limit=max_correction:~
            limit=round_step:0.5
            insulin_curve=walsh
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
            expected.replace("insulin_curve=walsh\n", ""),
            expected.replace("insulin_curve=walsh", "insulin_curve=exponential"),
            expected.replace("insulin_curve=walsh", "insulin_curve=Walsh"),
            expected.replace("limit=insulin_peak:~", "limit=insulin_peak:75.5"),
            expected.replace("limit=insulin_dia:4.5\n", ""),
            expected.replace("schema=3", "schema=2"),
            ProfileCodec.encode(limitsOnly()).replace("limit=round_step:0.5\n", "limit=round_step:0.5\ninsulin_curve=~\n"),
        ).forEach { assertNull(ProfileCodec.decode(it), it) }
    }

    @Test fun fingerprintSeesTheCurveDiaAndPeakAndAbsenceDiffersFromZero() {
        val sha = ProfileCodec.sha256(action())
        listOf(
            action(curve = Setting.Declared(InsulinCurveCode.BILINEAR)), action(curve = Setting.NotConfigured),
            action(dia = entered("5")), action(dia = ProfileValue.NotConfigured), action(dia = entered("0")),
            action(peak = entered("55")), action(peak = ProfileValue.NotConfigured), limitsOnly(),
        ).forEach { assertNotEquals(sha, ProfileCodec.sha256(it), it.toString()) }
    }

    @Test fun thePeakIsWholeMinutesAndNothingIsRounded() {
        assertTrue(ProfileLimit.INSULIN_PEAK.accepts(entered("75")))
        assertFalse(ProfileLimit.INSULIN_PEAK.accepts(entered("75.5")))
        assertTrue(ProfileLimit.INSULIN_DIA.accepts(entered("4.75")))
        assertTrue(ProfileLimit.INSULIN_PEAK.accepts(ProfileValue.NotConfigured))
        val e = assertFailsWith<IllegalArgumentException> { action(peak = entered("75.5")) }
        assertEquals(ProfileFailure.NOT_WHOLE_NUMBER.code, e.message)
    }

    @Test fun enteringAnActionValueUpgradesToSchemaThreeKeepingEveryEarlierValue() {
        val two = limitsOnly()
        val upgraded = two.withLimit(ProfileLimit.INSULIN_DIA, entered("4"))
        assertEquals(3, upgraded.schemaVersion)
        assertEquals(entered("0.5"), upgraded.limit(ProfileLimit.ROUND_STEP))
        assertEquals(ProfileValue.NotConfigured, upgraded.limit(ProfileLimit.MAX_BOLUS))
        assertEquals(ProfileValue.NotConfigured, upgraded.limit(ProfileLimit.INSULIN_PEAK))
        assertEquals(Setting.NotConfigured, upgraded.insulinCurve)
        val fromOne = content().withInsulinCurve(Setting.Declared(InsulinCurveCode.FIASP))
        assertEquals(3, fromOne.schemaVersion)
        assertEquals(Setting.Declared(InsulinCurveCode.FIASP), fromOne.insulinCurve)
        ProfileCatalog.limits(2)!!.forEach { assertEquals(ProfileValue.NotConfigured, fromOne.limit(it)) }
        // Entering a schema 2 limit on schema 1 still needs only schema 2 (ADR 0025 unchanged).
        assertEquals(2, content().withLimit(ProfileLimit.MAX_BOLUS, entered("10")).schemaVersion)
        // Clearing what the schema does not have changes nothing.
        assertSame(two, two.withInsulinCurve(Setting.NotConfigured))
        assertSame(two, two.withLimit(ProfileLimit.INSULIN_PEAK, ProfileValue.NotConfigured))
    }

    @Test fun editorReturnsToTheStartSchemaWhenTheActionIsCleared() {
        for (start in listOf(content(), limitsOnly())) {
            val editor = ProfileEditor(1, start, start, null)
            val typed = editor.withInsulinCurve(Setting.Declared(InsulinCurveCode.WALSH))
                .withLimit(ProfileLimit.INSULIN_DIA, entered("4"))
            assertEquals(3, typed.content.schemaVersion)
            val cleared = typed.withInsulinCurve(Setting.NotConfigured).withLimit(ProfileLimit.INSULIN_DIA, ProfileValue.NotConfigured)
            assertEquals(start, cleared.content)
            assertFalse(cleared.changed)
        }
        // A schema 1 start that gained a limit and an action value goes back only as far as its values allow.
        val one = content()
        val both = ProfileEditor(1, one, one, null).withLimit(ProfileLimit.MAX_BOLUS, entered("10"))
            .withLimit(ProfileLimit.INSULIN_PEAK, entered("75"))
        assertEquals(3, both.content.schemaVersion)
        assertEquals(2, both.withLimit(ProfileLimit.INSULIN_PEAK, ProfileValue.NotConfigured).content.schemaVersion)
    }

    @Test fun actionIsSavedOnceCarriedByLaterEditsAndOlderVersionsStayUnknown() {
        val repository = MemoryProfileRepository()
        val useCases = profiles(repository)
        useCases.saved(ProfileEditor(0, ProfileContent.empty(), limitsOnly(), null))
        val v2 = useCases.saved(useCases.edit(useCases.loaded()).withInsulinCurve(Setting.Declared(InsulinCurveCode.NOVORAPID))
            .withLimit(ProfileLimit.INSULIN_DIA, entered("5")))
        assertEquals(3, v2.content.schemaVersion)
        assertNull(useCases.loaded().version(1)!!.content.insulinCurve)
        val editor = useCases.edit(useCases.loaded())
        val v3 = useCases.saved(editor.with(editor.content.withDayValue(ProfileParameter.CARB_RATIO, entered("12")).content()))
        assertEquals(v2.content.insulinCurve, v3.content.insulinCurve)
        assertEquals(v2.content.limits, v3.content.limits)
        // A manual edit cannot drop schema 3, but restoring version 1 exactly keeps its unknown action.
        val downgrade = ProfileWrite(3, limitsOnly().withLimit(ProfileLimit.MAX_BOLUS, entered("9")), ProfileOrigin.MANUAL, null)
        assertEquals(ProfileFailure.SCHEMA_DOWNGRADE, (repository.save(downgrade, 1, "test/synthetic") as ProfileSave.Failed).reason)
        val restored = useCases.saved((useCases.restore(useCases.loaded(), 1) as ProfileRestore.Ready).editor)
        assertEquals(2, restored.content.schemaVersion)
        assertNull(restored.content.insulinCurve)
    }

    @Test fun theActionNeverBlocksCompletenessOrConfirmation() {
        val repository = MemoryProfileRepository()
        val useCases = profiles(repository)
        val fresh = ProfileContent.empty()
        val complete = fresh.copy(glucoseUnit = mgdl, timeZone = madrid, schedules = content().schedules)
        assertEquals(Setting.NotConfigured, complete.insulinCurve)
        assertEquals(ProfileCompleteness.Complete, complete.completeness(knownZones))
        useCases.saved(ProfileEditor(0, fresh, complete, null))
        val state = useCases.readState() as ProfileGateState.Evaluated
        assertTrue(state.canConfirm)
        assertTrue(useCases.record(useCases.confirmRequest(state, operationId(1))) is ConfirmationOutcome.Recorded)
    }

    @Test fun constructionRejectsACurveOutsideItsSchema() {
        val schedules = content().schedules
        assertFailsWith<IllegalArgumentException> {
            ProfileContent(2, mgdl, madrid, schedules, ProfileCatalog.unconfiguredLimits(2), Setting.NotConfigured)
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileContent(3, mgdl, madrid, schedules, ProfileCatalog.unconfiguredLimits(3), null)
        }
    }
}
