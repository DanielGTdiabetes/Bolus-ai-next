package org.bolusai.profile

import kotlin.test.*

class ProfileContentTest {
    private fun schedule(parameter: ProfileParameter, vararg segments: TimeSegment) =
        ParameterSchedule(parameter, segments.toList())

    private fun withRatio(ratio: ParameterSchedule) = ProfileContent.problem(1, mgdl, listOf(ratio,
        ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY),
        ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET)))

    @Test fun newProfileHasNoValuesOrDeclarations() {
        val empty = ProfileContent.empty()
        assertEquals(Setting.NotConfigured, empty.glucoseUnit)
        assertEquals(Setting.NotConfigured, empty.timeZone)
        assertEquals(ProfileCatalog.parameters(1), empty.schedules.map { it.parameter })
        empty.schedules.forEach {
            assertEquals(listOf(TimeSegment(0, 1440, ProfileValue.NotConfigured)), it.segments)
        }
    }

    @Test fun absenceAndZeroAreDistinctContent() {
        val zero = content(ratio = entered("0"))
        val missing = content(ratio = ProfileValue.NotConfigured)
        assertNotEquals(zero, missing)
        assertNotEquals(ProfileCodec.sha256(zero), ProfileCodec.sha256(missing))
    }

    @Test fun multipleContiguousSegmentsAreValidAndKeptAsWritten() {
        val ratio = schedule(ProfileParameter.CARB_RATIO, TimeSegment(0, 360, entered("8")),
            TimeSegment(360, 720, entered("8")), TimeSegment(720, 1440, ProfileValue.NotConfigured))
        assertNull(withRatio(ratio))
    }

    @Test fun gapsOverlapsAndIncompleteDaysAreRejected() {
        val cases = listOf(
            schedule(ProfileParameter.CARB_RATIO),
            schedule(ProfileParameter.CARB_RATIO, TimeSegment(0, 600, entered("8"))),
            schedule(ProfileParameter.CARB_RATIO, TimeSegment(60, 1440, entered("8"))),
            schedule(ProfileParameter.CARB_RATIO, TimeSegment(0, 600, entered("8")), TimeSegment(660, 1440, entered("9"))),
            schedule(ProfileParameter.CARB_RATIO, TimeSegment(0, 700, entered("8")), TimeSegment(600, 1440, entered("9"))),
            schedule(ProfileParameter.CARB_RATIO, TimeSegment(600, 1440, entered("9")), TimeSegment(0, 600, entered("8"))),
        )
        cases.forEach { assertEquals(ProfileFailure.INVALID_SEGMENTS, withRatio(it), it.toString()) }
    }

    @Test fun segmentsCannotCrossMidnightOrLeaveTheDay() {
        assertFailsWith<IllegalArgumentException> { TimeSegment(1320, 360, entered("8")) }
        assertFailsWith<IllegalArgumentException> { TimeSegment(0, 1441, entered("8")) }
        assertFailsWith<IllegalArgumentException> { TimeSegment(-1, 60, entered("8")) }
        assertFailsWith<IllegalArgumentException> { TimeSegment(60, 60, entered("8")) }
    }

    @Test fun catalogMustBeCompleteOrderedAndKnown() {
        val all = content().schedules
        assertEquals(ProfileFailure.INVALID_PARAMETERS, ProfileContent.problem(1, mgdl, all.drop(1)))
        assertEquals(ProfileFailure.INVALID_PARAMETERS, ProfileContent.problem(1, mgdl, all + all.first()))
        assertEquals(ProfileFailure.INVALID_PARAMETERS, ProfileContent.problem(1, mgdl, all.reversed()))
        assertEquals(ProfileFailure.INVALID_PARAMETERS, ProfileContent.problem(2, mgdl, all))
    }

    @Test fun multiSegmentScheduleIsNotEditableInThisPhase() {
        val split = ProfileContent(1, mgdl, madrid, listOf(
            schedule(ProfileParameter.CARB_RATIO, TimeSegment(0, 720, entered("8")), TimeSegment(720, 1440, entered("9"))),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET)))
        assertEquals(ProfileEdit.Rejected(ProfileFailure.SEGMENTS_UI_UNAVAILABLE),
            split.withAllDayValue(ProfileParameter.CARB_RATIO, entered("10")))
    }

    @Test fun timeZoneGrammarIsCheckedAndExistenceByPlatform() {
        listOf("Europe/Madrid", "UTC", "America/Argentina/Buenos_Aires", "Etc/GMT+1").forEach {
            assertTrue(ProfileTimeZone.isWellFormed(it), it)
        }
        listOf("", "+01:00", "Europe Madrid", "Europe/", "/Madrid", "Europe/Madrid\n", "a".repeat(65)).forEach {
            assertFalse(ProfileTimeZone.isWellFormed(it), it)
        }
        val profiles = profiles(MemoryProfileRepository())
        assertEquals(TimeZoneInput.Valid(Setting.NotConfigured), profiles.parseTimeZone("  "))
        assertEquals(TimeZoneInput.Valid(madrid), profiles.parseTimeZone(" Europe/Madrid "))
        assertEquals(TimeZoneInput.Invalid(ProfileFailure.INVALID_TIME_ZONE), profiles.parseTimeZone("Mars/Olympus"))
        assertEquals(TimeZoneInput.Invalid(ProfileFailure.INVALID_TIME_ZONE), profiles.parseTimeZone("+01:00"))
    }

    @Test fun unitLabelsAreDerivedAndAbsentWithoutUnit() {
        assertEquals("g/U", ProfileParameter.CARB_RATIO.unitLabel(Setting.NotConfigured))
        assertEquals("mg/dL/U", ProfileParameter.INSULIN_SENSITIVITY.unitLabel(mgdl))
        assertEquals("mmol/L", ProfileParameter.GLUCOSE_TARGET.unitLabel(mmol))
        assertNull(ProfileParameter.GLUCOSE_TARGET.unitLabel(Setting.NotConfigured))
    }
}
