package org.bolusai.profile

/**
 * Identity of one user intention to confirm or revoke (ADR 0014, section 4.2): a canonical lowercase UUID. It is
 * generated once by [OperationIds] when the user acts and reused by every retry of that same intention.
 */
data class OperationId(val value: String) {
    init { require(isWellFormed(value)) { "profile.confirmation.operation_id" } }

    companion object {
        private val PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        fun isWellFormed(value: String): Boolean = PATTERN.matches(value)
    }
}

/** Injectable source of operation identities; the platform supplies random UUIDs, tests supply fixed ones. */
fun interface OperationIds { fun next(): OperationId }

enum class ConfirmationEventKind(val code: String) {
    CONFIRM("confirm"), REVOKE("revoke");

    companion object {
        fun fromCode(code: String): ConfirmationEventKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Version 1 of the data-confirmation contract: the meaning of ADR 0014 section 1.1 and the preconditions of section
 * 2.3. A future clinical approval is another record, never another version of this contract.
 */
object ConfirmationContract {
    const val CURRENT = 1
    fun isKnown(version: Int): Boolean = version == CURRENT
}

internal fun isSha256Hex(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

/**
 * One append-only fact, separate from [ProfileContent] and outside every fingerprint. [seq] is the authoritative
 * order; [recordedAtEpochMs] is informative device time. Construction enforces the shape of each kind; the history
 * rules (contiguity, targets, single active confirmation) are enforced by [ProfileRecord.validate].
 */
data class ConfirmationEvent(
    val seq: Long,
    val operationId: OperationId,
    val kind: ConfirmationEventKind,
    val contractVersion: Int,
    val profileVersion: Long?,
    val contentSha256: String?,
    val revokesSeq: Long?,
    val observedEventSeq: Long,
    val recordedAtEpochMs: Long,
    val writer: String,
) {
    init {
        require(seq > 0) { "profile.confirmation.seq" }
        require(contractVersion > 0) { "profile.confirmation.contract" }
        require(observedEventSeq in 0 until seq) { "profile.confirmation.observed" }
        require(ProfileVersion.isValidWriter(writer)) { "profile.confirmation.writer" }
        when (kind) {
            ConfirmationEventKind.CONFIRM -> require(profileVersion != null && profileVersion > 0 &&
                contentSha256 != null && isSha256Hex(contentSha256) && revokesSeq == null) { "profile.confirmation.shape" }
            ConfirmationEventKind.REVOKE -> require(profileVersion == null && contentSha256 == null &&
                revokesSeq != null && revokesSeq in 1 until seq) { "profile.confirmation.shape" }
        }
    }

    /** Same stored payload as [request]: kind, target, observed state and contract. Time and writer are metadata. */
    fun sameIntention(request: ConfirmationRequest): Boolean =
        contractVersion == ConfirmationContract.CURRENT && observedEventSeq == request.observedEventSeq && when (request) {
            is ConfirmationRequest.Confirm -> kind == ConfirmationEventKind.CONFIRM &&
                profileVersion == request.profileVersion && contentSha256 == request.contentSha256
            is ConfirmationRequest.Revoke -> kind == ConfirmationEventKind.REVOKE && revokesSeq == request.revokesSeq
        }
}

/**
 * What the user decided on, built from a fresh read: the saved version and fingerprint shown, or the confirmation
 * shown, plus the last event that read observed ([observedEventSeq], 0 when none).
 */
sealed interface ConfirmationRequest {
    val operationId: OperationId
    val observedEventSeq: Long

    data class Confirm(
        override val operationId: OperationId,
        val profileVersion: Long,
        val contentSha256: String,
        override val observedEventSeq: Long,
    ) : ConfirmationRequest {
        init { require(profileVersion > 0 && isSha256Hex(contentSha256) && observedEventSeq >= 0) }
    }

    data class Revoke(
        override val operationId: OperationId,
        val revokesSeq: Long,
        override val observedEventSeq: Long,
    ) : ConfirmationRequest {
        init { require(revokesSeq > 0 && observedEventSeq >= 0) }
    }
}

/** Confirmation dimension of one version, always derived from the events and never stored as a flag. */
sealed interface VersionConfirmation {
    data object Unconfirmed : VersionConfirmation
    data class Active(val confirmation: ConfirmationEvent) : VersionConfirmation
    data class Revoked(val confirmation: ConfirmationEvent, val revocation: ConfirmationEvent) : VersionConfirmation
}

sealed interface ProfileRecordRead {
    data class Loaded(val record: ProfileRecord) : ProfileRecordRead
    data class Failed(val reason: ProfileFailure) : ProfileRecordRead
}

/**
 * Versions and confirmation events read together and proven consistent (ADR 0014, section 4.5). It can only be built
 * by [validate], so every consumer, including an idempotent retry, works on a history that was fully validated first.
 */
class ProfileRecord private constructor(
    /** [ProfileHistory.Missing] or [ProfileHistory.Loaded]; never [ProfileHistory.Failed]. */
    val history: ProfileHistory,
    /** Every event in [ConfirmationEvent.seq] order, contiguous from 1. */
    val events: List<ConfirmationEvent>,
) {
    val latest: ProfileVersion? get() = (history as? ProfileHistory.Loaded)?.latest
    val lastEventSeq: Long get() = events.lastOrNull()?.seq ?: 0

    fun event(seq: Long): ConfirmationEvent? = if (seq in 1..events.size.toLong()) events[(seq - 1).toInt()] else null
    fun eventByOperation(id: OperationId): ConfirmationEvent? = events.firstOrNull { it.operationId == id }
    fun revocationOf(confirmationSeq: Long): ConfirmationEvent? =
        events.firstOrNull { it.kind == ConfirmationEventKind.REVOKE && it.revokesSeq == confirmationSeq }

    /** The latest confirmation of [version] decides: none, still active, or revoked. Nothing is inherited. */
    fun confirmationOf(version: Long): VersionConfirmation {
        val last = events.lastOrNull { it.kind == ConfirmationEventKind.CONFIRM && it.profileVersion == version }
            ?: return VersionConfirmation.Unconfirmed
        val revocation = revocationOf(last.seq)
        return if (revocation == null) VersionConfirmation.Active(last) else VersionConfirmation.Revoked(last, revocation)
    }

    /** Unrevoked confirmations of earlier versions: history superseded by the latest version, never a fallback. */
    val supersededConfirmations: List<ConfirmationEvent>
        get() = latest?.let { current ->
            events.filter {
                it.kind == ConfirmationEventKind.CONFIRM && it.profileVersion != current.version && revocationOf(it.seq) == null
            }
        }.orEmpty()

    override fun equals(other: Any?): Boolean = other is ProfileRecord && other.history == history && other.events == events
    override fun hashCode(): Int = 31 * history.hashCode() + events.hashCode()
    override fun toString(): String = "ProfileRecord(history=$history, events=$events)"

    companion object {
        /**
         * Validates [versions] as ADR 0012 does and then every event in seq order. Any failure fails the whole record:
         * an unknown contract is [ProfileFailure.UNSUPPORTED_SCHEMA], anything else [ProfileFailure.INVALID_RECORD].
         * A record that cannot be proven is never shown as "unconfirmed".
         */
        fun validate(versions: List<ProfileVersion>, events: List<ConfirmationEvent>): ProfileRecordRead {
            val history = if (versions.isEmpty()) ProfileHistory.Missing else try {
                ProfileHistory.Loaded(versions.sortedByDescending { it.version })
            } catch (_: IllegalArgumentException) {
                return ProfileRecordRead.Failed(ProfileFailure.INVALID_RECORD)
            }
            if (events.any { !ConfirmationContract.isKnown(it.contractVersion) }) {
                return ProfileRecordRead.Failed(ProfileFailure.UNSUPPORTED_SCHEMA)
            }
            val ordered = events.sortedBy { it.seq }
            if (!eventsAreConsistent(history as? ProfileHistory.Loaded, ordered)) {
                return ProfileRecordRead.Failed(ProfileFailure.INVALID_RECORD)
            }
            return ProfileRecordRead.Loaded(ProfileRecord(history, ordered))
        }

        private fun eventsAreConsistent(history: ProfileHistory.Loaded?, events: List<ConfirmationEvent>): Boolean {
            if (events.map { it.seq } != (1L..events.size.toLong()).toList()) return false
            if (events.map { it.operationId }.toSet().size != events.size) return false
            val activeByVersion = mutableMapOf<Long, Long>()
            val revoked = mutableSetOf<Long>()
            for (event in events) {
                if (event.observedEventSeq != event.seq - 1) return false
                when (event.kind) {
                    ConfirmationEventKind.CONFIRM -> {
                        val number = event.profileVersion ?: return false
                        val version = history?.version(number) ?: return false
                        if (version.contentSha256 != event.contentSha256) return false
                        // Replaying the events, a version never has two active confirmations at once.
                        if (activeByVersion.containsKey(number)) return false
                        activeByVersion[number] = event.seq
                    }
                    ConfirmationEventKind.REVOKE -> {
                        val targetSeq = event.revokesSeq ?: return false
                        val target = events.getOrNull((targetSeq - 1).toInt()) ?: return false
                        if (target.kind != ConfirmationEventKind.CONFIRM || target.seq >= event.seq) return false
                        if (!revoked.add(targetSeq)) return false
                        val targetVersion = target.profileVersion ?: return false
                        if (activeByVersion[targetVersion] != targetSeq) return false
                        activeByVersion.remove(targetVersion)
                    }
                }
            }
            return true
        }
    }
}

/** Decision of the shared policy, evaluated by the storage adapter inside its exclusive transaction. */
sealed interface ConfirmationDecision {
    data class Insert(val event: ConfirmationEvent) : ConfirmationDecision
    /** The same intention was already committed; nothing is inserted. Historical, not necessarily current. */
    data class Replay(val event: ConfirmationEvent) : ConfirmationDecision
    data class Reject(val reason: ProfileFailure) : ConfirmationDecision
}

/** ADR 0014, section 7.1, steps 2 to 5. Step 1 is guaranteed by [ProfileRecord]. Rejections are never persisted. */
object ConfirmationPolicy {
    fun evaluate(
        request: ConfirmationRequest,
        recordedAtEpochMs: Long,
        writer: String,
        record: ProfileRecord,
        zones: TimeZoneRules,
    ): ConfirmationDecision {
        if (!ProfileVersion.isValidWriter(writer)) return ConfirmationDecision.Reject(ProfileFailure.INVALID_RECORD)
        record.eventByOperation(request.operationId)?.let { stored ->
            return if (stored.sameIntention(request)) ConfirmationDecision.Replay(stored)
            else ConfirmationDecision.Reject(ProfileFailure.CONFIRMATION_OPERATION_MISMATCH)
        }
        if (request.observedEventSeq != record.lastEventSeq) {
            return ConfirmationDecision.Reject(ProfileFailure.CONFIRMATION_STATE_CHANGED)
        }
        val latest = record.latest
        val seq = record.lastEventSeq + 1
        return when (request) {
            is ConfirmationRequest.Confirm -> when {
                latest == null || request.profileVersion != latest.version || request.contentSha256 != latest.contentSha256 ->
                    ConfirmationDecision.Reject(ProfileFailure.CONFIRMATION_STALE_VERSION)
                latest.completeness(zones) is ProfileCompleteness.Incomplete ->
                    ConfirmationDecision.Reject(ProfileFailure.CONFIRMATION_INCOMPLETE)
                record.confirmationOf(latest.version) is VersionConfirmation.Active ->
                    ConfirmationDecision.Reject(ProfileFailure.CONFIRMATION_ALREADY_ACTIVE)
                else -> ConfirmationDecision.Insert(ConfirmationEvent(seq, request.operationId, ConfirmationEventKind.CONFIRM,
                    ConfirmationContract.CURRENT, latest.version, latest.contentSha256, null, request.observedEventSeq,
                    recordedAtEpochMs, writer))
            }
            // Revoking requires neither completeness nor a recognized time zone (ADR 0014, section 2.3, E11).
            is ConfirmationRequest.Revoke -> {
                val active = latest?.let { record.confirmationOf(it.version) } as? VersionConfirmation.Active
                if (active == null || active.confirmation.seq != request.revokesSeq) {
                    ConfirmationDecision.Reject(ProfileFailure.CONFIRMATION_NOT_ACTIVE)
                } else ConfirmationDecision.Insert(ConfirmationEvent(seq, request.operationId, ConfirmationEventKind.REVOKE,
                    ConfirmationContract.CURRENT, null, null, request.revokesSeq, request.observedEventSeq, recordedAtEpochMs,
                    writer))
            }
        }
    }
}
