package org.bolusai.dexcomg7

/**
 * Local, append-only log of what the authenticated Dexcom G7 producer sent (ADR 0020). It records facts about
 * received messages; nothing in it is a validated glucose reading and every stored reading stays
 * NOT_APPROVED_FOR_USE. No freshness, range, unit, trend or identity rule is applied.
 */

/** SHA-256 of the transmitter identifier (decision E3). The identifier itself is never stored. */
public class TransmitterFingerprint(sha256Hex: String) {
    public val sha256Hex: String = sha256Hex.also {
        require(SHA256_HEX.matches(it)) { "dexcom_observation.invalid_transmitter_fingerprint" }
    }

    override fun equals(other: Any?): Boolean = other is TransmitterFingerprint && other.sha256Hex == sha256Hex
    override fun hashCode(): Int = sha256Hex.hashCode()
    override fun toString(): String = "TransmitterFingerprint(${sha256Hex.take(8)}…)"

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** Platform hashing, injected so the shared log needs no crypto dependency. */
public fun interface TransmitterHasher {
    public fun fingerprint(transmitterId: String): TransmitterFingerprint
}

/** Opaque identifiers for messages and observations, injected for deterministic tests. */
public fun interface ObservationIds {
    public fun next(): String
}

/**
 * The sender identity that authentication proved for one message (ADR 0020, E9): package, exact version code and the
 * SHA-256 digests of the current APK signers. Stored with every message so its provenance survives later changes of
 * the trust policy. The UID is not kept: it is not stable across reinstalls.
 */
public class AuthenticatedSender(
    public val packageName: String,
    public val versionCode: Long,
    signerCertificateSha256: Set<String>,
) {
    public val signerCertificateSha256: List<String> = signerCertificateSha256.sorted()

    init {
        require(packageName.isNotBlank() && packageName == packageName.trim() && ',' !in packageName) {
            "dexcom_observation.invalid_sender_package"
        }
        require(versionCode > 0) { "dexcom_observation.invalid_sender_version" }
        require(this.signerCertificateSha256.isNotEmpty() && this.signerCertificateSha256.all { SHA256_HEX.matches(it) }) {
            "dexcom_observation.invalid_sender_signers"
        }
    }

    override fun equals(other: Any?): Boolean = other is AuthenticatedSender && other.packageName == packageName &&
        other.versionCode == versionCode && other.signerCertificateSha256 == signerCertificateSha256

    override fun hashCode(): Int = (packageName.hashCode() * 31 + versionCode.hashCode()) * 31 +
        signerCertificateSha256.hashCode()

    override fun toString(): String = "AuthenticatedSender($packageName, $versionCode, signers=${signerCertificateSha256.size})"

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** What one authenticated broadcast produced. Sender rejections are never recorded (ADR 0020, E5). */
public sealed interface ObservationInput {
    public val receivedAtEpochMs: Long
    public val sender: AuthenticatedSender

    public data class Observed(
        override val receivedAtEpochMs: Long,
        override val sender: AuthenticatedSender,
        val message: ObservedDexcomG7Message,
    ) : ObservationInput {
        init {
            require(message.authenticatedPackage == sender.packageName) { "dexcom_observation.sender_mismatch" }
        }
    }

    public data class PayloadRejected(
        override val receivedAtEpochMs: Long,
        override val sender: AuthenticatedSender,
        val rejection: DexcomG7PayloadRejection,
    ) : ObservationInput

    public data class PayloadUnreadable(
        override val receivedAtEpochMs: Long,
        override val sender: AuthenticatedSender,
    ) : ObservationInput
}

public enum class StoredMessageOutcome(public val code: String) {
    OBSERVED("observed"),
    PAYLOAD_REJECTED("payload_rejected"),
    PAYLOAD_UNREADABLE("payload_unreadable"),
}

/**
 * One received message. A rejection keeps only its code, contract field name and canonical index (decision E1),
 * never content. The counts describe what happened to its readings when it was recorded.
 */
public data class StoredMessage(
    val messageId: String,
    val receivedAtEpochMs: Long,
    val sender: AuthenticatedSender,
    val outcome: StoredMessageOutcome,
    val rejection: DexcomG7PayloadRejection?,
    val newReadings: Int,
    val duplicateReadings: Int,
    val conflictingReadings: Int,
    val collapsedInMessage: Int,
    val unknownTopLevelFields: List<String>,
    val unknownReadingFields: List<String>,
    val unreportableUnknownFieldCount: Int,
) {
    public val contract: String get() = ObservedDexcomG7Contract.ID

    init {
        require(messageId.isNotBlank()) { "dexcom_observation.blank_message_id" }
        require((outcome == StoredMessageOutcome.PAYLOAD_REJECTED) == (rejection != null)) {
            "dexcom_observation.rejection_mismatch"
        }
        require(outcome == StoredMessageOutcome.OBSERVED || newReadings + duplicateReadings + conflictingReadings +
            collapsedInMessage + unknownTopLevelFields.size + unknownReadingFields.size + unreportableUnknownFieldCount == 0) {
            "dexcom_observation.counts_without_readings"
        }
        require(listOf(newReadings, duplicateReadings, conflictingReadings, collapsedInMessage,
            unreportableUnknownFieldCount).all { it >= 0 }) { "dexcom_observation.negative_count" }
    }
}

/**
 * One stored observation of a reading. [conflictOf] links a reading whose transmitter and producer timestamp match
 * an earlier observation with different content to that first observation (decision E2). Nothing is overwritten.
 */
public data class StoredReading(
    val observationId: String,
    val messageId: String,
    val index: Int,
    val transmitter: TransmitterFingerprint,
    val producerTimestamp: Long,
    val producerGlucoseValue: Int,
    val producerTrendArrow: String,
    val egvTxTime: Long,
    val conflictOf: String?,
) {
    public val status: ObservedReadingStatus get() = ObservedReadingStatus.NOT_APPROVED_FOR_USE

    init {
        require(observationId.isNotBlank() && messageId.isNotBlank()) { "dexcom_observation.blank_id" }
        require(index >= 0) { "dexcom_observation.negative_index" }
        require(conflictOf != observationId) { "dexcom_observation.self_conflict" }
    }

    internal fun sameContent(other: StoredReading): Boolean =
        transmitter == other.transmitter &&
            producerTimestamp == other.producerTimestamp &&
            producerGlucoseValue == other.producerGlucoseValue &&
            producerTrendArrow == other.producerTrendArrow &&
            egvTxTime == other.egvTxTime
}

/** What a store must append, all or nothing, for one input. */
public class ObservationPlan(public val message: StoredMessage, readings: List<StoredReading>) {
    public val readings: List<StoredReading> = readings.toList()
}

/**
 * Pure decision of what to append (ADR 0020). [existing] returns every stored observation with that transmitter
 * fingerprint and producer timestamp, read inside the store's transaction. A reading identical to any of them is a
 * duplicate and is not stored again; otherwise, if any exists, it is stored as a conflict of the first one;
 * otherwise it is new. Readings of the same message are already unique by timestamp (ADR 0018, D3).
 */
public object ObservationPolicy {
    public fun plan(
        input: ObservationInput,
        ids: ObservationIds,
        hasher: TransmitterHasher,
        existing: (TransmitterFingerprint, Long) -> List<StoredReading>,
    ): ObservationPlan {
        val messageId = ids.next()
        return when (input) {
            is ObservationInput.PayloadUnreadable -> ObservationPlan(
                empty(messageId, input, StoredMessageOutcome.PAYLOAD_UNREADABLE, null), emptyList())
            is ObservationInput.PayloadRejected -> ObservationPlan(
                empty(messageId, input, StoredMessageOutcome.PAYLOAD_REJECTED, input.rejection), emptyList())
            is ObservationInput.Observed -> observed(messageId, input, ids, hasher, existing)
        }
    }

    private fun observed(
        messageId: String,
        input: ObservationInput.Observed,
        ids: ObservationIds,
        hasher: TransmitterHasher,
        existing: (TransmitterFingerprint, Long) -> List<StoredReading>,
    ): ObservationPlan {
        val stored = ArrayList<StoredReading>()
        var duplicates = 0
        var conflicts = 0
        for (reading in input.message.readings) {
            val transmitter = hasher.fingerprint(reading.transmitterId)
            val candidate = StoredReading(
                observationId = ids.next(),
                messageId = messageId,
                index = reading.index,
                transmitter = transmitter,
                producerTimestamp = reading.producerTimestamp,
                producerGlucoseValue = reading.producerGlucoseValue,
                producerTrendArrow = reading.producerTrendArrow,
                egvTxTime = reading.egvTxTime,
                conflictOf = null,
            )
            val previous = existing(transmitter, reading.producerTimestamp)
            check(previous.all { it.transmitter == transmitter && it.producerTimestamp == reading.producerTimestamp }) {
                "dexcom_observation.store_returned_another_key"
            }
            when {
                previous.any { it.sameContent(candidate) } -> duplicates++
                previous.isEmpty() -> stored += candidate
                else -> {
                    val first = previous.singleOrNull { it.conflictOf == null }
                        ?: error("dexcom_observation.missing_first_observation")
                    stored += candidate.copy(conflictOf = first.observationId)
                    conflicts++
                }
            }
        }
        val message = input.message
        return ObservationPlan(
            StoredMessage(
                messageId = messageId,
                receivedAtEpochMs = input.receivedAtEpochMs,
                sender = input.sender,
                outcome = StoredMessageOutcome.OBSERVED,
                rejection = null,
                newReadings = stored.size - conflicts,
                duplicateReadings = duplicates,
                conflictingReadings = conflicts,
                collapsedInMessage = message.collapsedDuplicateCount,
                unknownTopLevelFields = message.unknownTopLevelFields,
                unknownReadingFields = message.unknownReadingFields,
                unreportableUnknownFieldCount = message.unreportableUnknownFieldCount,
            ),
            stored,
        )
    }

    private fun empty(
        messageId: String,
        input: ObservationInput,
        outcome: StoredMessageOutcome,
        rejection: DexcomG7PayloadRejection?,
    ) = StoredMessage(messageId, input.receivedAtEpochMs, input.sender, outcome, rejection, 0, 0, 0, 0, emptyList(),
        emptyList(), 0)
}

public enum class ObservationFailure(public val code: String) {
    WRITE_FAILED("dexcom_observation.write_failed"),
    READ_FAILED("dexcom_observation.read_failed"),
    CORRUPT_STORAGE("dexcom_observation.corrupt_storage"),
    UNSUPPORTED_SCHEMA("dexcom_observation.unsupported_schema"),
}

public sealed interface ObservationWrite {
    /** Visible only after the transaction, including its commit, returned. */
    public data class Recorded(val message: StoredMessage, val readings: List<StoredReading>) : ObservationWrite

    public data class Failed(val reason: ObservationFailure) : ObservationWrite
}

public sealed interface ObservationRead {
    public data class Loaded(val messages: List<StoredMessage>, val readings: List<StoredReading>) : ObservationRead

    public data class Failed(val reason: ObservationFailure) : ObservationRead
}

/** Persistence port. Implementations run [ObservationPolicy] and the appends inside one transaction. */
public interface DexcomObservationStore {
    public fun record(input: ObservationInput): ObservationWrite

    public fun read(): ObservationRead
}
