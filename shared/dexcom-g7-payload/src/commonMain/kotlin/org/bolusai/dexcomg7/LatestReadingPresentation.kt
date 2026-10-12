package org.bolusai.dexcomg7

/**
 * The last recorded message and the readings stored for it, read from the log without loading the history
 * (ADR 0023, plan step 5). [message] is null when nothing was ever recorded.
 */
public data class ObservationLatest(val message: StoredMessage?, val readings: List<StoredReading>) {
    init {
        require(message != null || readings.isEmpty()) { "dexcom_observation.readings_without_message" }
        require(readings.all { it.messageId == message?.messageId }) { "dexcom_observation.foreign_reading" }
    }

    public companion object {
        /** Reference definition over a loaded log: the last message in storage order and its stored readings. */
        public fun of(read: ObservationRead.Loaded): ObservationLatest {
            val last = read.messages.lastOrNull()
            return ObservationLatest(last, read.readings.filter { it.messageId == last?.messageId })
        }
    }
}

public sealed interface ObservationLatestRead {
    public data class Loaded(val latest: ObservationLatest) : ObservationLatestRead

    public data class Failed(val reason: ObservationFailure) : ObservationLatestRead
}

/**
 * Whether the reception barrier lets new messages in, as the platform established it. Only [OPEN] allows showing a
 * value: a closed gate never shows an earlier value, so nothing old looks new (ADR 0023, C6).
 */
public enum class ReceptionGate(public val code: String) {
    OPEN("open"),
    RECEIVER_DISABLED("dexcom_presentation.receiver_disabled"),
    NEXT_PERMISSION_MISSING("dexcom_presentation.next_permission_missing"),
    POLICY_PENDING("dexcom_presentation.policy_pending"),
    DURABLE_BLOCK("dexcom_presentation.durable_block"),
}

/** Why no value is shown. Stable codes; the UI translates them. */
public enum class NoReadingCause(public val code: String) {
    RECEIVER_DISABLED("dexcom_presentation.receiver_disabled"),
    NEXT_PERMISSION_MISSING("dexcom_presentation.next_permission_missing"),
    POLICY_PENDING("dexcom_presentation.policy_pending"),
    DURABLE_BLOCK("dexcom_presentation.durable_block"),
    NO_READING_RECORDED("dexcom_presentation.no_reading_recorded"),
    LOG_UNREADABLE("dexcom_presentation.log_unreadable"),
    LAST_MESSAGE_REJECTED("dexcom_presentation.last_message_rejected"),

    /**
     * Technical consequence of C2 (b), not a new clinical rule: the last message carried several readings and their
     * order is not contracted, or it only repeated a reading already stored, so no single reading identifies "the last
     * received" until B1 is verified.
     */
    SELECTION_PENDING_B1("dexcom_presentation.selection_pending_b1"),
}

/** Why a reading is shown without its number. */
public enum class WithheldCause(public val code: String) {
    /** B4 (b): outside 1..400, the interval the producer was observed to emit. Presentation filter, not a clinical range. */
    OUTSIDE_PRODUCER_INTERVAL("dexcom_presentation.outside_producer_interval"),

    /** C2: the reading has a conflicting observation (ADR 0020, E2). */
    CONFLICT("dexcom_presentation.conflict"),

    /** The device clock is earlier than the stored reception instant: no elapsed time can be stated. */
    RECEPTION_AFTER_NOW("dexcom_presentation.reception_after_now"),
}

/**
 * What Inicio and Bolo may show (ADR 0023, part II). Before B1 is approved: selection and elapsed time come from the
 * reception instant only, the measurement time is "not verified", freshness has no approved policy and the value is
 * never called current. The unit is the producer's claim, mg/dL, never converted.
 */
public sealed interface LatestReadingPresentation {
    public data class NoReading(val cause: NoReadingCause, val detail: String?) : LatestReadingPresentation

    public data class Withheld(
        val cause: WithheldCause,
        val receivedAtEpochMs: Long,
        val provenance: MessageProvenance,
    ) : LatestReadingPresentation

    public data class Reading(
        val valueMgDl: Int,
        val receivedAtEpochMs: Long,
        val elapsedSinceReceptionMs: Long,
        val provenance: MessageProvenance,
        /** First 8 hex characters of the transmitter fingerprint, for details only (C3). */
        val transmitterShort: String,
        /** Producer literal kept for details only (B5): never translated to an arrow. */
        val trendLiteral: String,
    ) : LatestReadingPresentation {
        val unit: ProducerUnitClaim get() = ProducerUnitClaim.MG_DL_STATED_BY_PRODUCER
        val measurementTimeVerified: Boolean get() = false
        val freshness: String get() = FRESHNESS_POLICY_NOT_APPROVED
        val status: ObservedReadingStatus get() = ObservedReadingStatus.NOT_APPROVED_FOR_USE
    }

    public companion object {
        public const val FRESHNESS_POLICY_NOT_APPROVED: String = "dexcom_presentation.freshness_policy_not_approved"
    }
}

/**
 * Pure use case `PresentLatestDexcomReading(log, clock)` of ADR 0023 (plan step 5), applying C2 (b), C3, C5 (a), C6
 * and B4 (b) while B1 is unverified. It never reads the producer timestamp to order, compare or age a reading.
 */
public object PresentLatestDexcomReading {
    public const val PRODUCER_INTERVAL_MIN: Int = 1
    public const val PRODUCER_INTERVAL_MAX: Int = 400

    public fun present(
        gate: ReceptionGate,
        gateDetail: String?,
        log: ObservationLatestRead,
        nowEpochMs: Long,
    ): LatestReadingPresentation {
        when (gate) {
            ReceptionGate.OPEN -> Unit
            ReceptionGate.RECEIVER_DISABLED -> return none(NoReadingCause.RECEIVER_DISABLED, gateDetail)
            ReceptionGate.NEXT_PERMISSION_MISSING -> return none(NoReadingCause.NEXT_PERMISSION_MISSING, gateDetail)
            ReceptionGate.POLICY_PENDING -> return none(NoReadingCause.POLICY_PENDING, gateDetail)
            ReceptionGate.DURABLE_BLOCK -> return none(NoReadingCause.DURABLE_BLOCK, gateDetail)
        }
        val latest = when (log) {
            is ObservationLatestRead.Failed -> return none(NoReadingCause.LOG_UNREADABLE, log.reason.code)
            is ObservationLatestRead.Loaded -> log.latest
        }
        val message = latest.message ?: return none(NoReadingCause.NO_READING_RECORDED, null)
        if (message.outcome != StoredMessageOutcome.OBSERVED) {
            return none(NoReadingCause.LAST_MESSAGE_REJECTED, message.rejection?.code?.code ?: message.outcome.code)
        }
        val received = message.newReadings + message.duplicateReadings + message.conflictingReadings
        val reading = latest.readings.singleOrNull()
        if (received != 1 || reading == null) return none(NoReadingCause.SELECTION_PENDING_B1, "readings=$received")
        val elapsed = nowEpochMs - message.receivedAtEpochMs
        return when {
            reading.conflictOf != null ->
                LatestReadingPresentation.Withheld(WithheldCause.CONFLICT, message.receivedAtEpochMs, message.provenance)
            reading.producerGlucoseValue !in PRODUCER_INTERVAL_MIN..PRODUCER_INTERVAL_MAX ->
                LatestReadingPresentation.Withheld(WithheldCause.OUTSIDE_PRODUCER_INTERVAL, message.receivedAtEpochMs,
                    message.provenance)
            elapsed < 0 ->
                LatestReadingPresentation.Withheld(WithheldCause.RECEPTION_AFTER_NOW, message.receivedAtEpochMs,
                    message.provenance)
            else -> LatestReadingPresentation.Reading(
                valueMgDl = reading.producerGlucoseValue,
                receivedAtEpochMs = message.receivedAtEpochMs,
                elapsedSinceReceptionMs = elapsed,
                provenance = message.provenance,
                transmitterShort = reading.transmitter.sha256Hex.take(8),
                trendLiteral = reading.producerTrendArrow,
            )
        }
    }

    private fun none(cause: NoReadingCause, detail: String?) = LatestReadingPresentation.NoReading(cause, detail)
}
