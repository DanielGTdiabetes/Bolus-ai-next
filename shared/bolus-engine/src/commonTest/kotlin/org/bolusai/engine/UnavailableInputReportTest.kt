package org.bolusai.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnavailableInputReportTest {
    @Test
    fun emptyReportIsRejectedRatherThanInterpretedAsValidInputs(): Unit {
        val error = assertFailsWith<IllegalArgumentException> {
            UnavailableInputReport(emptyList())
        }
        assertEquals("unavailable_input_report.empty", error.message)
    }

    @Test
    fun repeatedIdenticalCausesAreDeduplicatedWithoutLosingDifferentCauses(): Unit {
        val unknown = UnavailableInput(InputKind.IOB, UnavailabilityReason.UNKNOWN)
        val incomplete = UnavailableInput(InputKind.IOB, UnavailabilityReason.INCOMPLETE)
        val report = UnavailableInputReport(listOf(unknown, incomplete, unknown))

        assertEquals(1, report.contractVersion)
        assertEquals(listOf(incomplete, unknown), report.entries)
    }

    @Test
    fun orderingIsIndependentOfProducerOrderForEveryCause(): Unit {
        val causes = InputKind.entries.flatMap { input ->
            UnavailabilityReason.entries.map { reason -> UnavailableInput(input, reason) }
        }
        val expected = causes.map { it.code }.sorted()

        for (offset in causes.indices) {
            val reordered = causes.drop(offset) + causes.take(offset)
            assertEquals(expected, UnavailableInputReport(reordered + reordered.reversed()).entries.map { it.code })
        }
    }

    @Test
    fun sourceFailuresRemainDistinctFromClinicalAvailabilityReports(): Unit {
        val report = UnavailableInputReport(
            listOf(
                UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.PERMISSION_DENIED),
                UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.POLICY_NOT_APPROVED),
                UnavailableInput(InputKind.PROFILE, UnavailabilityReason.AUTHENTICATION_FAILED),
                UnavailableInput(InputKind.IOB, UnavailabilityReason.PERSISTENCE_FAILED),
            ),
        )

        assertEquals(
            listOf(
                "input.glucose.permission_denied",
                "input.glucose.policy_not_approved",
                "input.iob.persistence_failed",
                "input.profile.authentication_failed",
            ),
            report.entries.map { it.code },
        )
    }

    @Test
    fun mutatingTheProducerListDoesNotRewriteTheSnapshot(): Unit {
        val missing = UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.MISSING)
        val unknown = UnavailableInput(InputKind.IOB, UnavailabilityReason.UNKNOWN)
        val producer = mutableListOf(missing, unknown)
        val report = UnavailableInputReport(producer)

        producer.clear()
        producer.add(UnavailableInput(InputKind.MEAL, UnavailabilityReason.INVALID))

        assertEquals(listOf(missing, unknown), report.entries)
    }

    @Test
    fun mutatingReturnedEntriesCannotRewriteTheSnapshot(): Unit {
        val missing = UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.MISSING)
        val unknown = UnavailableInput(InputKind.IOB, UnavailabilityReason.UNKNOWN)
        val report = UnavailableInputReport(listOf(missing, unknown))
        val exposed = report.entries

        // Some platforms expose a mutable backing list; others reject mutation.
        try {
            (exposed as MutableList<UnavailableInput>).clear()
        } catch (_: ClassCastException) {
        } catch (_: UnsupportedOperationException) {
        }

        assertEquals(listOf(missing, unknown), report.entries)
    }
}
