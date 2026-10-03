package org.bolusai.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** ADR 0015, section C.2: report v2. Synthetic technical identifiers only. */
class InputUnavailabilityReportTest {
    private fun cause(input: InputKind, reason: UnavailabilityReasonV2, vararg details: String) =
        InputUnavailability(input, reason, details.toList())

    private val policy = cause(InputKind.PROFILE, UnavailabilityReasonV2.POLICY_NOT_APPROVED, "profile.not_approved_for_calculation")
    private val unconfirmed = cause(InputKind.PROFILE, UnavailabilityReasonV2.UNCONFIRMED, "profile.confirmation.missing")
    private val glucose = cause(InputKind.GLUCOSE, UnavailabilityReasonV2.POLICY_NOT_APPROVED)

    @Test
    fun emptyReportIsRejectedWithItsStableIdentifier(): Unit {
        val error = assertFailsWith<IllegalArgumentException> { InputUnavailabilityReport(emptyList()) }
        assertEquals(InputUnavailabilityErrors.REPORT_EMPTY, error.message)
        assertEquals("input_unavailability_report.empty", error.message)
    }

    @Test
    fun causesAreOrderedByCodeIndependentlyOfTheProducer(): Unit {
        val causes = InputKind.entries.flatMap { input ->
            UnavailabilityReasonV2.entries.filter { it.isAdmittedFor(input) }.map { InputUnavailability(input, it, emptyList()) }
        }
        val expected = causes.map { it.code }.sorted()
        for (offset in causes.indices) {
            val reordered = causes.drop(offset) + causes.take(offset)
            val report = InputUnavailabilityReport(reordered + reordered.reversed())
            assertEquals(expected, report.entries.map { it.code })
            assertEquals(2, report.contractVersion)
        }
    }

    @Test
    fun detailsOfTheSameInputAndReasonAreMergedOrderedAndWithoutDuplicates(): Unit {
        val superseded = cause(InputKind.PROFILE, UnavailabilityReasonV2.UNCONFIRMED,
            "profile.confirmation.superseded", "profile.confirmation.missing")
        val report = InputUnavailabilityReport(listOf(superseded, policy, unconfirmed, glucose))
        assertEquals(
            listOf("input.glucose.policy_not_approved", "input.profile.policy_not_approved", "input.profile.unconfirmed"),
            report.entries.map { it.code },
        )
        assertEquals(listOf("profile.confirmation.missing", "profile.confirmation.superseded"), report.entries[2].details)
        assertEquals(report, InputUnavailabilityReport(listOf(glucose, unconfirmed, superseded, policy, policy)))
    }

    @Test
    fun differentReasonsOfTheSameInputAreNeverMerged(): Unit {
        val incomplete = cause(InputKind.PROFILE, UnavailabilityReasonV2.INCOMPLETE, "profile.gate.incomplete")
        val invalid = cause(InputKind.PROFILE, UnavailabilityReasonV2.INVALID, "profile.gate.time_zone_unrecognized")
        val report = InputUnavailabilityReport(listOf(invalid, incomplete))
        assertEquals(listOf(incomplete, invalid), report.entries)
    }

    @Test
    fun theUnionPassesTheLimitAgainAndIsRejectedRatherThanTruncated(): Unit {
        val first = cause(InputKind.PROFILE, UnavailabilityReasonV2.INVALID, *(1..8).map { "profile.a$it" }.toTypedArray())
        val second = cause(InputKind.PROFILE, UnavailabilityReasonV2.INVALID, *(1..8).map { "profile.b$it" }.toTypedArray())
        assertEquals(16, InputUnavailabilityReport(listOf(first, second)).entries.single().details.size)
        // Overlapping details count once.
        val overlap = cause(InputKind.PROFILE, UnavailabilityReasonV2.INVALID, "profile.a1", "profile.b8")
        assertEquals(16, InputUnavailabilityReport(listOf(first, second, overlap)).entries.single().details.size)

        val third = cause(InputKind.PROFILE, UnavailabilityReasonV2.INVALID, "profile.c1")
        val error = assertFailsWith<IllegalArgumentException> { InputUnavailabilityReport(listOf(first, second, third)) }
        assertEquals(InputUnavailabilityErrors.TOO_MANY_DETAILS, error.message)
    }

    @Test
    fun mutatingTheProducerListDoesNotRewriteTheReport(): Unit {
        val producer = mutableListOf(policy, unconfirmed)
        val report = InputUnavailabilityReport(producer)
        producer.clear()
        producer += glucose
        assertEquals(listOf(policy, unconfirmed), report.entries)
    }

    @Test
    fun mutatingReturnedEntriesOrDetailsDoesNotRewriteTheReport(): Unit {
        val report = InputUnavailabilityReport(listOf(policy, unconfirmed))
        val exposed = report.entries
        try {
            (exposed as MutableList<InputUnavailability>).clear()
        } catch (_: ClassCastException) {
        } catch (_: UnsupportedOperationException) {
        }
        try {
            (report.entries[1].details as MutableList<String>).clear()
        } catch (_: ClassCastException) {
        } catch (_: UnsupportedOperationException) {
        }
        assertEquals(listOf(policy, unconfirmed), report.entries)
        assertEquals(listOf("profile.confirmation.missing"), report.entries[1].details)
    }

    @Test
    fun elevationOfAV1ReportKeepsEveryCodeWithEmptyDetail(): Unit {
        val v1 = UnavailableInputReport(InputKind.entries.flatMap { input ->
            UnavailabilityReason.entries.map { UnavailableInput(input, it) }
        })
        val v2 = InputUnavailabilityReport.from(v1)
        assertEquals(v1.entries.map { it.code }, v2.entries.map { it.code })
        assertEquals(52, v2.entries.size)
        v2.entries.forEach { assertEquals(emptyList(), it.details) }
    }
}
