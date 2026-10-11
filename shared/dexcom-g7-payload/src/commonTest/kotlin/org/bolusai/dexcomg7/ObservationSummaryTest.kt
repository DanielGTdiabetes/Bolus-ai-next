package org.bolusai.dexcomg7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Synthetic log only (ADR 0021). */
class ObservationSummaryTest {
    private val sender = AuthenticatedSender("example.sender", 42, setOf("a".repeat(64)))
    private val newer = AuthenticatedSender("example.sender", 43, setOf("b".repeat(64)))
    private val fingerprint = TransmitterFingerprint("c".repeat(64))

    private fun message(id: String, at: Long, outcome: StoredMessageOutcome, code: DexcomG7RejectionCode? = null,
                        duplicates: Int = 0, collapsed: Int = 0, from: AuthenticatedSender = sender) =
        StoredMessage(id, at, from, outcome, code?.let { DexcomG7PayloadRejection(it) }, 0, duplicates, 0, collapsed,
            emptyList(), emptyList(), 0)

    private fun reading(id: String, conflictOf: String? = null, value: Int = 123) =
        StoredReading(id, "m1", 0, fingerprint, 10, value, "SYNTHETIC_TREND", 5, conflictOf)

    @Test fun emptyLogHasNoLastMessage() {
        val summary = ObservationSummary.of(ObservationRead.Loaded(emptyList(), emptyList()))
        assertEquals(0, summary.messages)
        assertNull(summary.lastReceivedAtEpochMs)
        assertNull(summary.lastSender)
        assertEquals(ObservedReadingStatus.NOT_APPROVED_FOR_USE, summary.status)
    }

    @Test fun countsEveryOutcomeAndKeepsTheLastSender() {
        val read = ObservationRead.Loaded(
            listOf(
                // Codex review on PR #47: duplicates collapsed inside a message are counted separately.
                message("m1", 1, StoredMessageOutcome.OBSERVED, duplicates = 2, collapsed = 3),
                message("m2", 2, StoredMessageOutcome.PAYLOAD_REJECTED, DexcomG7RejectionCode.WRONG_TYPE),
                message("m3", 3, StoredMessageOutcome.PAYLOAD_REJECTED, DexcomG7RejectionCode.EMPTY_BATCH),
                message("m4", 4, StoredMessageOutcome.PAYLOAD_REJECTED, DexcomG7RejectionCode.WRONG_TYPE),
                message("m5", 5, StoredMessageOutcome.PAYLOAD_UNREADABLE, from = newer),
            ),
            listOf(reading("o1"), reading("o2", conflictOf = "o1", value = 99)),
        )
        assertEquals(
            ObservationSummary(
                messages = 5,
                observed = 1,
                unreadable = 1,
                rejectedByCode = mapOf("dexcom_g7.payload.empty_batch" to 1, "dexcom_g7.payload.wrong_type" to 2),
                storedReadings = 2,
                duplicates = 2,
                collapsedInMessage = 3,
                conflicts = 1,
                lastReceivedAtEpochMs = 5,
                lastSender = newer,
            ),
            ObservationSummary.of(read),
        )
    }

    @Test fun theSummaryCarriesNoGlucoseValueOrTrend() {
        val summary = ObservationSummary.of(ObservationRead.Loaded(
            listOf(message("m1", 1, StoredMessageOutcome.OBSERVED)), listOf(reading("o1", value = 187))))
        val printed = summary.toString()
        assertFalse(printed.contains("187"), printed)
        assertFalse(printed.contains("SYNTHETIC_TREND"), printed)
        assertFalse(printed.contains("c".repeat(16)), printed)
    }
}
