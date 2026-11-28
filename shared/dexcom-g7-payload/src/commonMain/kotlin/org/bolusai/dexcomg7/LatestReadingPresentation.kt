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
     * The last message stored no reading of its own: it only repeated readings already stored. Since B1 was approved
     * (2026-11-28) a message with several readings selects the one measured last; this cause remains for a message
     * that brings nothing new, because the store reads only the readings of the last message. The code keeps its name.
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

    /** C4 (a): the producer timestamp is later than the reception of its own message, compared in whole seconds. */
    FUTURE_TIMESTAMP("dexcom_presentation.future_timestamp"),

    /** The producer timestamp is negative: it is not an instant after the epoch and no age can be stated. */
    INVALID_TIMESTAMP("dexcom_presentation.invalid_timestamp"),
}

/**
 * Whether a reading counts as current glucose. Owner decision of 2026-11-28 (ADR 0023, C5): a reading is current up
 * to [DexcomReadingValidity.MAX_AGE_MS] after its measurement time, inclusive. Older, it is expired and is never used
 * as current glucose. Always evaluated against an explicit instant.
 */
public enum class ReadingFreshness(public val code: String) {
    CURRENT("dexcom_presentation.current"),
    EXPIRED("dexcom_presentation.expired"),
}

/** Validity of a Dexcom reading. Provenance: owner decision of 2026-11-28; 10 minutes, as observed in Legacy. */
public object DexcomReadingValidity {
    public const val MAX_AGE_MS: Long = 10L * 60L * 1000L
    public const val POLICY_VERSION: String = "owner-2026-11-28-10min"
}

/**
 * What Inicio and Bolo may show (ADR 0023, part II). Since the owner approved B1 and the validity on 2026-11-28, the
 * producer timestamp is the measurement time in whole seconds since the epoch (UTC), the age counts from it and a
 * reading is current only within [DexcomReadingValidity.MAX_AGE_MS]. The unit is the producer claim, mg/dL, never
 * converted.
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
        /** Producer timestamp as an instant: whole seconds since the epoch (B1, approved 2026-11-28). */
        val measuredAtEpochMs: Long,
        /** Age at the instant of the read that produced this presentation. Use [freshnessAt] for any later instant. */
        val ageMs: Long,
        val receivedAtEpochMs: Long,
        val provenance: MessageProvenance,
        /** First 8 hex characters of the transmitter fingerprint, for details only (C3). */
        val transmitterShort: String,
        /** Producer literal kept for details only (B5): never translated to an arrow. */
        val trendLiteral: String,
    ) : LatestReadingPresentation {
        val unit: ProducerUnitClaim get() = ProducerUnitClaim.MG_DL_STATED_BY_PRODUCER
        val measurementTimeVerified: Boolean get() = true
        val status: ObservedReadingStatus get() = ObservedReadingStatus.NOT_APPROVED_FOR_USE

        /** Freshness when this presentation was read. */
        val freshness: ReadingFreshness get() = freshnessOfAge(ageMs)

        /**
         * Freshness at [nowEpochMs]. An instant earlier than the measurement is not current: a reading can only be
         * current at or after its measurement and within the validity.
         */
        public fun freshnessAt(nowEpochMs: Long): ReadingFreshness =
            if (nowEpochMs < measuredAtEpochMs) ReadingFreshness.EXPIRED else freshnessOfAge(nowEpochMs - measuredAtEpochMs)

        private fun freshnessOfAge(age: Long): ReadingFreshness =
            if (age in 0..DexcomReadingValidity.MAX_AGE_MS) ReadingFreshness.CURRENT else ReadingFreshness.EXPIRED
    }
}

/**
 * Pure use case `PresentLatestDexcomReading(log, clock)` of ADR 0023 (plan step 5), applying C3, C4 (a), C6, B4 (b)
 * and, since the owner approved B1 on 2026-11-28, the producer timestamp as measurement time: among the readings the
 * last message stored, the one measured last is selected and aged from that instant.
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
        if (latest.readings.isEmpty()) return none(NoReadingCause.SELECTION_PENDING_B1, "readings=$received")
        val lastMeasured = latest.readings.maxOf { it.producerTimestamp }
        fun withheld(cause: WithheldCause) =
            LatestReadingPresentation.Withheld(cause, message.receivedAtEpochMs, message.provenance)
        // Two stored readings with the same measurement time cannot both be the last one: no number is shown.
        val reading = latest.readings.singleOrNull { it.producerTimestamp == lastMeasured }
            ?: return withheld(WithheldCause.CONFLICT)
        // C4 (a): whole seconds, never rounded in favour of the reading.
        val receivedSeconds = floorSeconds(message.receivedAtEpochMs)
        return when {
            reading.conflictOf != null -> withheld(WithheldCause.CONFLICT)
            reading.producerGlucoseValue !in PRODUCER_INTERVAL_MIN..PRODUCER_INTERVAL_MAX ->
                withheld(WithheldCause.OUTSIDE_PRODUCER_INTERVAL)
            nowEpochMs < message.receivedAtEpochMs -> withheld(WithheldCause.RECEPTION_AFTER_NOW)
            reading.producerTimestamp < 0 -> withheld(WithheldCause.INVALID_TIMESTAMP)
            reading.producerTimestamp > receivedSeconds -> withheld(WithheldCause.FUTURE_TIMESTAMP)
            else -> LatestReadingPresentation.Reading(
                valueMgDl = reading.producerGlucoseValue,
                measuredAtEpochMs = reading.producerTimestamp * 1000L,
                ageMs = nowEpochMs - reading.producerTimestamp * 1000L,
                receivedAtEpochMs = message.receivedAtEpochMs,
                provenance = message.provenance,
                transmitterShort = reading.transmitter.sha256Hex.take(8),
                trendLiteral = reading.producerTrendArrow,
            )
        }
    }

    private fun floorSeconds(epochMs: Long): Long {
        val quotient = epochMs / 1000L
        return if (epochMs % 1000L < 0) quotient - 1 else quotient
    }

    private fun none(cause: NoReadingCause, detail: String?) = LatestReadingPresentation.NoReading(cause, detail)
}
