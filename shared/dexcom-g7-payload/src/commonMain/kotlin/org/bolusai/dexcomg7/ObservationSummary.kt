package org.bolusai.dexcomg7

/** Result of summarising the log. Stores aggregate instead of loading the whole history (ADR 0021, F5). */
public sealed interface ObservationSummaryRead {
    public data class Loaded(val summary: ObservationSummary) : ObservationSummaryRead

    public data class Failed(val reason: ObservationFailure) : ObservationSummaryRead
}

/**
 * Counts and status of the observation log for diagnostics (ADR 0021, F1). It deliberately carries no glucose value,
 * trend, producer timestamp or transmitter fingerprint, so nothing in it can be read as a current reading.
 */
public data class ObservationSummary(
    val messages: Int,
    val observed: Int,
    val unreadable: Int,
    val rejectedByCode: Map<String, Int>,
    val storedReadings: Int,
    val duplicates: Int,
    val collapsedInMessage: Int,
    val conflicts: Int,
    val lastReceivedAtEpochMs: Long?,
    val lastProvenance: MessageProvenance?,
) {
    public val status: ObservedReadingStatus get() = ObservedReadingStatus.NOT_APPROVED_FOR_USE

    public companion object {
        /**
         * Reference definition over a loaded log; storage aggregates must equal it. Message order is storage order:
         * the last message is the last one recorded.
         */
        public fun of(read: ObservationRead.Loaded): ObservationSummary {
            val messages = read.messages
            val last = messages.lastOrNull()
            return ObservationSummary(
                messages = messages.size,
                observed = messages.count { it.outcome == StoredMessageOutcome.OBSERVED },
                unreadable = messages.count { it.outcome == StoredMessageOutcome.PAYLOAD_UNREADABLE },
                rejectedByCode = messages.mapNotNull { it.rejection?.code?.code }.groupingBy { it }.eachCount()
                    .toSortedMap().toMap(),
                storedReadings = read.readings.size,
                duplicates = messages.sumOf { it.duplicateReadings },
                collapsedInMessage = messages.sumOf { it.collapsedInMessage },
                conflicts = read.readings.count { it.conflictOf != null },
                lastReceivedAtEpochMs = last?.receivedAtEpochMs,
                lastProvenance = last?.provenance,
            )
        }
    }
}
