package org.bolusai.next

import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.next.glucose.dexcom.DexcomReceiverComponent
import org.bolusai.next.glucose.dexcom.DexcomReceiverStatus
import org.bolusai.next.glucose.dexcom.DexcomSenderTrustPolicy
import org.bolusai.next.glucose.dexcom.HolderSnapshot
import org.bolusai.next.glucose.dexcom.PermissionHolder
import org.bolusai.next.ui.DexcomLogBlock
import org.bolusai.next.ui.DexcomLogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** ADR 0021 and ADR 0022 (R9): the Diagnóstico lines of the Dexcom log and receiver. Synthetic summaries only. */
class DexcomLogBlockTest {
    private val receiver = DexcomReceiverStatus(DexcomReceiverComponent.DISABLED_BY_DEFAULT, DexcomSenderTrustPolicy.Pending)
    private val receiverLines = listOf("dexcom_receiver.component=disabled_by_default", "dexcom_receiver.policy=pending")

    @Test
    fun `pending and failed reads show only their code`() {
        assertEquals(receiverLines + "dexcom_log.read.pending", DexcomLogBlock.lines(DexcomLogState.Reading, receiver))
        assertEquals(receiverLines + "dexcom_observation.corrupt_storage",
            DexcomLogBlock.lines(DexcomLogState.Failed(ObservationFailure.CORRUPT_STORAGE), receiver))
    }

    @Test
    fun `an empty log says so without inventing a reception`() {
        val summary = ObservationSummary(0, 0, 0, emptyMap(), 0, 0, 0, 0, null, null)
        assertEquals(
            receiverLines + listOf(
                "dexcom_log.status=dexcom_g7.reading.not_approved_for_use",
                "dexcom_log.messages=0",
                "dexcom_log.observed=0",
                "dexcom_log.unreadable=0",
                "dexcom_log.readings_stored=0",
                "dexcom_log.duplicates=0",
                "dexcom_log.collapsed_in_message=0",
                "dexcom_log.conflicts=0",
                "dexcom_log.last_received_utc=none",
                "dexcom_log.last_provenance=none",
            ),
            DexcomLogBlock.lines(DexcomLogState.Loaded(summary), receiver),
        )
    }

    @Test
    fun `a loaded log shows counts, rejections, last reception and sender only`() {
        val summary = ObservationSummary(
            messages = 4, observed = 2, unreadable = 1,
            rejectedByCode = mapOf("dexcom_g7.payload.wrong_type" to 1),
            storedReadings = 3, duplicates = 1, collapsedInMessage = 2, conflicts = 1,
            lastReceivedAtEpochMs = 1_790_000_000_000,
            lastProvenance = AuthenticatedSender("example.sender", 42, setOf("a".repeat(64))),
        )
        val lines = DexcomLogBlock.lines(DexcomLogState.Loaded(summary), receiver)
        assertEquals(
            receiverLines + listOf(
                "dexcom_log.status=dexcom_g7.reading.not_approved_for_use",
                "dexcom_log.messages=4",
                "dexcom_log.observed=2",
                "dexcom_log.unreadable=1",
                "dexcom_log.rejected.dexcom_g7.payload.wrong_type=1",
                "dexcom_log.readings_stored=3",
                "dexcom_log.duplicates=1",
                "dexcom_log.collapsed_in_message=2",
                "dexcom_log.conflicts=1",
                "dexcom_log.last_received_utc=2026-09-21T14:13:20Z",
                "dexcom_log.last_provenance=authenticated_sender:example.sender@42",
            ),
            lines,
        )
        assertFalse(lines.any { "a".repeat(16) in it })
    }

    @Test
    fun `reception lines of the permission gate follow the log and never carry glucose`() {
        val status = DexcomReceiverStatus(DexcomReceiverComponent.ENABLED, DexcomSenderTrustPolicy.Pending,
            DexcomReceiverStatus.receptionLines(false, HolderSnapshot.Read(false, null,
                listOf(PermissionHolder("org.bolusai.next", 1, 1, setOf("e".repeat(64)), setOf("org.bolusai.next"))),
                "org.bolusai.next", 1)))
        assertEquals(listOf(
            "dexcom_receiver.component=enabled",
            "dexcom_receiver.policy=pending",
            "dexcom_log.read.pending",
            "dexcom_reception.policy=adr0023-i2b-2026-10-12",
            "dexcom_reception.next_permission=missing",
            "dexcom_reception.gate_now=dexcom_reception.visibility_unproven",
            "dexcom_reception.full_visibility=false",
            "dexcom_reception.visible_holders=org.bolusai.next",
        ), DexcomLogBlock.lines(DexcomLogState.Reading, status))
        assertEquals(listOf("dexcom_reception.policy=adr0023-i2b-2026-10-12", "dexcom_reception.next_permission=granted",
            "dexcom_reception.gate_now=dexcom_reception.holders_query_failed", "dexcom_reception.snapshot=query_failed"),
            DexcomReceiverStatus.receptionLines(true, HolderSnapshot.QueryFailed))
    }
}
