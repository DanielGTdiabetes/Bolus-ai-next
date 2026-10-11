package org.bolusai.next.ui

import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationSummary
import java.time.Instant

/** State of the Diagnóstico view of the Dexcom observation log (ADR 0021). */
internal sealed interface DexcomLogState {
    data object Reading : DexcomLogState
    data class Loaded(val summary: ObservationSummary) : DexcomLogState
    data class Failed(val reason: ObservationFailure) : DexcomLogState
}

/**
 * Technical lines of the folded block (ADR 0021, F1 and F3): counts, status, last reception and sender only. No
 * glucose value, trend, producer timestamp or transmitter fingerprint is ever printed.
 */
internal object DexcomLogBlock {
    fun lines(state: DexcomLogState): List<String> = when (state) {
        DexcomLogState.Reading -> listOf("dexcom_log.read.pending")
        is DexcomLogState.Failed -> listOf(state.reason.code)
        is DexcomLogState.Loaded -> with(state.summary) {
            listOf(
                "dexcom_log.status=${status.code}",
                "dexcom_log.messages=$messages",
                "dexcom_log.observed=$observed",
                "dexcom_log.unreadable=$unreadable",
            ) + rejectedByCode.map { (code, count) -> "dexcom_log.rejected.$code=$count" } + listOf(
                "dexcom_log.readings_stored=$storedReadings",
                "dexcom_log.duplicates=$duplicates",
                "dexcom_log.conflicts=$conflicts",
                "dexcom_log.last_received_utc=${lastReceivedAtEpochMs?.let { Instant.ofEpochMilli(it).toString() } ?: "none"}",
                "dexcom_log.last_sender=${lastSender?.let { "${it.packageName}@${it.versionCode}" } ?: "none"}",
            )
        }
    }
}
