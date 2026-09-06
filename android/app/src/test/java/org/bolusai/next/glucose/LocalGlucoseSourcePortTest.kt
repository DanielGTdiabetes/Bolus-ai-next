package org.bolusai.next.glucose

import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalGlucoseSourcePortTest {
    @Test
    fun `default source blocks because the Dexcom policy is not approved`() {
        val status = ReadLocalGlucoseStatus(PendingDexcomSource).execute()

        assertEquals(InputKind.GLUCOSE, status.input)
        assertEquals(UnavailabilityReason.POLICY_NOT_APPROVED, status.reason)
        assertEquals("input.glucose.policy_not_approved", status.code)
    }

    @Test
    fun `every unavailable source reason crosses the boundary unchanged`() {
        val statuses = UnavailabilityReason.entries.map { reason ->
            ReadLocalGlucoseStatus(sourceReturning(reason)).execute()
        }

        assertEquals(UnavailabilityReason.entries, statuses.map { it.reason })
        assertEquals(
            UnavailabilityReason.entries.map { "input.glucose.${it.code}" },
            statuses.map { it.code },
        )
        statuses.forEach { assertEquals(InputKind.GLUCOSE, it.input) }
    }

    @Test
    fun `permission source missing and invalid states remain distinct`() {
        val reasons = listOf(
            UnavailabilityReason.MISSING,
            UnavailabilityReason.PERMISSION_DENIED,
            UnavailabilityReason.SOURCE_UNAVAILABLE,
            UnavailabilityReason.INVALID,
        )

        val codes = reasons.map { reason ->
            ReadLocalGlucoseStatus(sourceReturning(reason)).execute().code
        }

        assertEquals(
            listOf(
                "input.glucose.missing",
                "input.glucose.permission_denied",
                "input.glucose.source_unavailable",
                "input.glucose.invalid",
            ),
            codes,
        )
    }

    @Test
    fun `unexpected adapter failure is not relabeled as a clinical state`() {
        val failure = IllegalStateException("local_glucose_source.unexpected_failure")
        val source = LocalGlucoseSourcePort { throw failure }

        val observed = assertThrows(IllegalStateException::class.java) {
            ReadLocalGlucoseStatus(source).execute()
        }

        assertEquals(failure, observed)
    }

    private fun sourceReturning(reason: UnavailabilityReason): LocalGlucoseSourcePort =
        LocalGlucoseSourcePort { LocalGlucoseSourceResult.Unavailable(reason) }
}
