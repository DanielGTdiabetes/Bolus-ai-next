package org.bolusai.profile

import kotlin.test.*

/** ADR 0014, section 3. Structural rules only; no clinical range is checked anywhere. */
class ProfileCompletenessTest {
    private val isf = ProfileParameter.INSULIN_SENSITIVITY

    @Test fun completeContentWithExplicitZerosIsComplete() {
        assertEquals(ProfileCompleteness.Complete, content().completeness(knownZones))
        // An explicit 0 is present: it counts as configured and is neither blocked nor judged here.
        assertEquals(ProfileCompleteness.Complete,
            content(ratio = entered("0"), sensitivity = entered("0"), target = entered("0")).completeness(knownZones))
    }

    @Test fun emptyProfileReportsEveryGapInCatalogOrder() {
        assertEquals(ProfileCompleteness.Incomplete(listOf(
            CompletenessGap.UnitNotDeclared,
            CompletenessGap.TimeZoneNotDeclared,
            CompletenessGap.ValueNotConfigured(ProfileParameter.CARB_RATIO, 0, 1440),
            CompletenessGap.ValueNotConfigured(ProfileParameter.INSULIN_SENSITIVITY, 0, 1440),
            CompletenessGap.ValueNotConfigured(ProfileParameter.GLUCOSE_TARGET, 0, 1440),
        )), ProfileContent.empty().completeness(knownZones))
    }

    @Test fun absenceIsAGapInEveryParameterWhileZeroIsNot() {
        ProfileParameter.entries.forEach { parameter ->
            val missing = content().withSchedule(parameter, segment(0, 1440))
            assertEquals(ProfileCompleteness.Incomplete(listOf(CompletenessGap.ValueNotConfigured(parameter, 0, 1440))),
                missing.completeness(knownZones), parameter.code)
            val zero = content().withSchedule(parameter, segment(0, 1440, entered("0")))
            assertEquals(ProfileCompleteness.Complete, zero.completeness(knownZones), parameter.code)
        }
    }

    @Test fun oneEmptySegmentAmongConfiguredOnesIsReportedExactly() {
        val split = content().withSchedule(isf, segment(0, 360, entered("40")), segment(360, 720),
            segment(720, 1440, entered("45")))
        assertEquals(ProfileCompleteness.Incomplete(listOf(CompletenessGap.ValueNotConfigured(isf, 360, 720))),
            split.completeness(knownZones))
        val twoGaps = content(unit = Setting.NotConfigured, sensitivity = ProfileValue.NotConfigured,
            target = ProfileValue.NotConfigured).withSchedule(ProfileParameter.CARB_RATIO, segment(0, 60, entered("8")),
            segment(60, 600), segment(600, 1440), )
        assertEquals(listOf(
            CompletenessGap.UnitNotDeclared,
            CompletenessGap.ValueNotConfigured(ProfileParameter.CARB_RATIO, 60, 600),
            CompletenessGap.ValueNotConfigured(ProfileParameter.CARB_RATIO, 600, 1440),
            CompletenessGap.ValueNotConfigured(isf, 0, 1440),
            CompletenessGap.ValueNotConfigured(ProfileParameter.GLUCOSE_TARGET, 0, 1440),
        ), (twoGaps.completeness(knownZones) as ProfileCompleteness.Incomplete).gaps)
    }

    @Test fun timeZoneMustBeDeclaredAndRecognizedByThePlatformNow() {
        assertEquals(ProfileCompleteness.Incomplete(listOf(CompletenessGap.TimeZoneNotDeclared)),
            content(zone = Setting.NotConfigured).completeness(knownZones))
        val zones = MutableZones("Europe/Madrid")
        assertEquals(ProfileCompleteness.Complete, content().completeness(zones))
        zones.known.clear()
        assertEquals(ProfileCompleteness.Incomplete(listOf(CompletenessGap.TimeZoneUnrecognized("Europe/Madrid"))),
            content().completeness(zones))
    }

    @Test fun moreThanFortyEightCompleteSegmentsAreComplete() {
        val many = content().withSchedule(ProfileParameter.CARB_RATIO,
            *(0 until 60).map { segment(it * 24, (it + 1) * 24, entered("${it + 1}")) }.toTypedArray())
        assertEquals(ProfileCompleteness.Complete, many.completeness(knownZones))
    }

    @Test fun equalAdjacentSegmentsDoNotAffectCompleteness() {
        val equal = content().withSchedule(ProfileParameter.GLUCOSE_TARGET, segment(0, 720, entered("110")),
            segment(720, 1440, entered("110")))
        assertEquals(ProfileCompleteness.Complete, equal.completeness(knownZones))
    }

    @Test fun unitChangeFromADeclaredUnitIsIncompleteByConstruction() {
        val changed = content().withGlucoseUnit(mmol)
        assertEquals(listOf(
            CompletenessGap.ValueNotConfigured(isf, 0, 1440),
            CompletenessGap.ValueNotConfigured(ProfileParameter.GLUCOSE_TARGET, 0, 1440),
        ), (changed.completeness(knownZones) as ProfileCompleteness.Incomplete).gaps)
    }

    @Test fun gapCodesAreStable() {
        assertEquals(listOf("unit_not_declared", "time_zone_not_declared", "time_zone_unrecognized", "value_not_configured"),
            listOf(CompletenessGap.UnitNotDeclared, CompletenessGap.TimeZoneNotDeclared,
                CompletenessGap.TimeZoneUnrecognized("X"), CompletenessGap.ValueNotConfigured(isf, 0, 1440)).map { it.code })
        assertFailsWith<IllegalArgumentException> { ProfileCompleteness.Incomplete(emptyList()) }
    }
}
