package org.bolusai.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0029: fibre and Warsaw settings in content schema 4. No defaults, no ranges, `0` is a configured value. */
class ProfileNutritionRulesTest {
    private val rules = ProfileLimit.of(ProfileFieldGroup.NUTRITION_RULES)
    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))

    private fun schema3() = ProfileContent(3, Setting.Declared(GlucoseUnit.MG_DL), Setting.Declared(ProfileTimeZone("Europe/Madrid")),
        ProfileParameter.entries.map { ParameterSchedule.allDay(it, entered("10")) })

    @Test fun theRulesAreExactlyTheFiveLegacySettings() {
        assertEquals(listOf("fiber_factor", "fiber_threshold", "warsaw_factor_simple", "warsaw_factor_dual", "warsaw_trigger"),
            rules.map { it.code })
        assertEquals(listOf(ProfileLimit.WARSAW_TRIGGER), rules.filter { it.wholeNumber })
    }

    @Test fun newProfilesStartWithEveryRuleNotConfiguredAndNothingDefaulted() {
        val empty = ProfileContent.empty()
        assertEquals(4, empty.schemaVersion)
        rules.forEach { assertEquals(ProfileValue.NotConfigured, empty.limit(it), it.code) }
    }

    @Test fun earlierSchemasHaveNoRulesAndKeepThemUnknown() {
        val old = schema3()
        rules.forEach { assertNull(old.limit(it), it.code) }
        assertEquals(old, old.withLimit(ProfileLimit.FIBER_FACTOR, ProfileValue.NotConfigured))
        assertFalse(ProfileLimit.FIBER_FACTOR in ProfileCatalog.limits(3).orEmpty())
    }

    @Test fun enteringOneRuleMovesToSchemaFourAndLeavesTheOthersNotConfigured() {
        val next = schema3().withLimit(ProfileLimit.INSULIN_DIA, entered("4"))
            .withLimit(ProfileLimit.WARSAW_FACTOR_SIMPLE, entered("0.1"))
        assertEquals(4, next.schemaVersion)
        assertEquals(entered("0.1"), next.limit(ProfileLimit.WARSAW_FACTOR_SIMPLE))
        assertEquals(entered("4"), next.limit(ProfileLimit.INSULIN_DIA))
        (rules - ProfileLimit.WARSAW_FACTOR_SIMPLE).forEach { assertEquals(ProfileValue.NotConfigured, next.limit(it), it.code) }
        // Clearing the only rule lets an editor return to the schema it started from.
        assertEquals(3, next.withLimit(ProfileLimit.WARSAW_FACTOR_SIMPLE, ProfileValue.NotConfigured).compactedTo(3).schemaVersion)
    }

    @Test fun zeroIsAConfiguredValueThatSwitchesARuleOffAndIsNotTheSameAsNotConfigured() {
        val off = ProfileContent.empty().withLimit(ProfileLimit.FIBER_FACTOR, entered("0"))
        assertEquals(entered("0"), off.limit(ProfileLimit.FIBER_FACTOR))
        assertNotEquals(ProfileCodec.sha256(ProfileContent.empty()), ProfileCodec.sha256(off))
    }

    @Test fun theTriggerIsWholeKcalAndIsNeverRounded() {
        assertTrue(ProfileLimit.WARSAW_TRIGGER.accepts(entered("300")))
        assertFalse(ProfileLimit.WARSAW_TRIGGER.accepts(entered("300.5")))
        assertTrue(ProfileLimit.FIBER_THRESHOLD.accepts(entered("5.5")))
    }

    @Test fun theCanonicalTextCarriesEveryRuleAndRoundTrips() {
        val content = ProfileContent.empty()
            .withLimit(ProfileLimit.FIBER_FACTOR, entered("0.5")).withLimit(ProfileLimit.FIBER_THRESHOLD, entered("5"))
            .withLimit(ProfileLimit.WARSAW_FACTOR_SIMPLE, entered("0.1")).withLimit(ProfileLimit.WARSAW_TRIGGER, entered("300"))
        val text = ProfileCodec.encode(content)
        for (line in listOf("schema=4", "limit=fiber_factor:0.5", "limit=fiber_threshold:5", "limit=warsaw_factor_simple:0.1",
            "limit=warsaw_factor_dual:~", "limit=warsaw_trigger:300")) {
            assertTrue(line in text.lines(), line)
        }
        assertEquals(content, ProfileCodec.decode(text))
        // A schema 3 text has no rule lines, so its fingerprint did not change with schema 4.
        assertFalse(ProfileCodec.encode(schema3()).contains("fiber"))
    }
}
