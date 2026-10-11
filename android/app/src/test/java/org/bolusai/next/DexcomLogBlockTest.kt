package org.bolusai.next

import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.next.ui.DexcomLogBlock
import org.bolusai.next.ui.DexcomLogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** ADR 0021: the Diagnóstico lines of the Dexcom log. Synthetic summaries only. */
class DexcomLogBlockTest {
    @Test
    fun `pending and failed reads show only their code`() {
        assertEquals(listOf("dexcom_log.read.pending"), DexcomLogBlock.lines(DexcomLogState.Reading))
        assertEquals(listOf("dexcom_observation.corrupt_storage"),
            DexcomLogBlock.lines(DexcomLogState.Failed(ObservationFailure.CORRUPT_STORAGE)))
    }

    @Test
    fun `an empty log says so without inventing a reception`() {
        val summary = ObservationSummary(0, 0, 0, emptyMap(), 0, 0, 0, null, null)
        assertEquals(
            listOf(
                "dexcom_log.status=dexcom_g7.reading.not_approved_for_use",
                "dexcom_log.messages=0",
                "dexcom_log.observed=0",
                "dexcom_log.unreadable=0",
                "dexcom_log.readings_stored=0",
                "dexcom_log.duplicates=0",
                "dexcom_log.conflicts=0",
                "dexcom_log.last_received_utc=none",
                "dexcom_log.last_sender=none",
            ),
            DexcomLogBlock.lines(DexcomLogState.Loaded(summary)),
        )
    }

    @Test
    fun `a loaded log shows counts, rejections, last reception and sender only`() {
        val summary = ObservationSummary(
            messages = 4, observed = 2, unreadable = 1,
            rejectedByCode = mapOf("dexcom_g7.payload.wrong_type" to 1),
            storedReadings = 3, duplicates = 1, conflicts = 1,
            lastReceivedAtEpochMs = 1_790_000_000_000,
            lastSender = AuthenticatedSender("example.sender", 42, setOf("a".repeat(64))),
        )
        val lines = DexcomLogBlock.lines(DexcomLogState.Loaded(summary))
        assertEquals(
            listOf(
                "dexcom_log.status=dexcom_g7.reading.not_approved_for_use",
                "dexcom_log.messages=4",
                "dexcom_log.observed=2",
                "dexcom_log.unreadable=1",
                "dexcom_log.rejected.dexcom_g7.payload.wrong_type=1",
                "dexcom_log.readings_stored=3",
                "dexcom_log.duplicates=1",
                "dexcom_log.conflicts=1",
                "dexcom_log.last_received_utc=2026-09-21T14:13:20Z",
                "dexcom_log.last_sender=example.sender@42",
            ),
            lines,
        )
        assertFalse(lines.any { "a".repeat(16) in it })
    }
}
