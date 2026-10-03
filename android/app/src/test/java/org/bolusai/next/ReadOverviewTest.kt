package org.bolusai.next

import org.bolusai.engine.UnavailabilityReason
import org.bolusai.next.application.ReadOverview
import org.bolusai.next.glucose.LocalGlucoseSourcePort
import org.bolusai.next.glucose.LocalGlucoseSourceResult
import org.bolusai.next.glucose.PendingDexcomSource
import org.bolusai.next.glucose.ReadLocalGlucoseStatus
import org.junit.Assert.*
import org.junit.Test

class ReadOverviewTest {
    @Test fun unapprovedProfileUnknownIobAndMissingMealStayExplicitAndBlock() {
        val state = ReadOverview(ReadLocalGlucoseStatus(PendingDexcomSource)).execute()
        assertEquals("input.glucose.policy_not_approved", state.glucose.code)
        // ADR 0015, D7: static and provable without reading the profile; never the unproven "missing".
        assertEquals("input.profile.policy_not_approved", state.profile.code)
        assertEquals("input.iob.unknown", state.iob.code)
        assertEquals("input.meal.missing", state.meal.code)
        assertFalse(state.allowsCalculation)
        assertFalse(state.allowsTreatment)
    }

    @Test fun everyGlucoseCauseIsPreservedWithoutEnablingTreatment() {
        UnavailabilityReason.entries.forEach { reason ->
            val source = LocalGlucoseSourcePort { LocalGlucoseSourceResult.Unavailable(reason) }
            val state = ReadOverview(ReadLocalGlucoseStatus(source)).execute()
            assertEquals(reason, state.glucose.reason)
            assertFalse(state.allowsCalculation)
            assertFalse(state.allowsTreatment)
        }
    }

    @Test fun sourceFailureIsNotRelabeledAsOfflineOrEmptyData() {
        val failure = IllegalStateException("synthetic failure")
        val source = LocalGlucoseSourcePort { throw failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            ReadOverview(ReadLocalGlucoseStatus(source)).execute()
        })
    }
}
