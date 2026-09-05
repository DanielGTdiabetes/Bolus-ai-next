package org.bolusai.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UnavailableInputTest {
    @Test
    fun publicIdentifiersRemainStable(): Unit {
        assertEquals(listOf("glucose", "profile", "iob", "meal"), InputKind.entries.map { it.code })
        assertEquals(
            listOf(
                "missing", "invalid", "expired", "unknown", "incomplete", "conflicting",
                "permission_denied", "source_unavailable", "authentication_failed",
                "clock_anomaly", "parse_failed", "persistence_failed", "policy_not_approved",
            ),
            UnavailabilityReason.entries.map { it.code },
        )
    }

    @Test
    fun everyReportedCauseHasADistinctVersionedIdentifier(): Unit {
        val reports = InputKind.entries.flatMap { input ->
            UnavailabilityReason.entries.map { reason -> UnavailableInput(input, reason) }
        }
        assertEquals(reports.size, reports.map { it.code }.toSet().size)
        reports.forEach { assertEquals(1, it.contractVersion) }
        assertEquals("input.iob.unknown", UnavailableInput(InputKind.IOB, UnavailabilityReason.UNKNOWN).code)
    }

    @Test
    fun unknownAndIncompleteHistoryAreDifferentReports(): Unit {
        val unknown = UnavailableInput(InputKind.IOB, UnavailabilityReason.UNKNOWN)
        val incomplete = UnavailableInput(InputKind.IOB, UnavailabilityReason.INCOMPLETE)
        assertNotEquals(unknown, incomplete)
        assertNotEquals(unknown.code, incomplete.code)
        assertEquals(unknown, unknown.copy())
    }

    @Test
    fun unapprovedFreshnessPolicyDoesNotReportExpiredGlucose(): Unit {
        assertNotEquals(
            UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.POLICY_NOT_APPROVED),
            UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.EXPIRED),
        )
    }
}
