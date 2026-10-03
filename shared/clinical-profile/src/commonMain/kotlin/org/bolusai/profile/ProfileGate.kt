package org.bolusai.profile

/** Clinical eligibility dimension. In this increment it has a single value: blocked (ADR 0014, section 2.1). */
enum class ClinicalEligibility(val code: String) {
    NOT_APPROVED(ProfileVersion.NOT_APPROVED_CODE),
}

/** Stable domain detail codes of the gate (ADR 0014, sections 2.2 and 6.2). They never authorize anything. */
object ProfileGateCodes {
    const val READ_PENDING = "profile.read.pending"
    const val HISTORY_MISSING = "profile.history.missing"
    const val INCOMPLETE = "profile.gate.incomplete"
    const val TIME_ZONE_UNRECOGNIZED = "profile.gate.time_zone_unrecognized"
    const val CONFIRMATION_MISSING = "profile.confirmation.missing"
    const val CONFIRMATION_SUPERSEDED = "profile.confirmation.superseded"
    const val CONFIRMATION_REVOKED = "profile.confirmation.revoked"
    const val NOT_APPROVED = ProfileVersion.NOT_APPROVED_CODE
}

/**
 * The four separated dimensions of ADR 0014 section 2.1: read and integrity, structural completeness of the latest
 * version, its confirmation, and clinical eligibility. A dimension is only evaluated when the previous one is proven.
 * Every state blocks calculation and treatment; "confirmed" never means usable.
 */
sealed interface ProfileGateState {
    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false
    val eligibility: ClinicalEligibility get() = ClinicalEligibility.NOT_APPROVED
    val blockCode: String get() = ProfileVersion.NOT_APPROVED_CODE

    /** Domain detail codes in a deterministic order (no priority implied). */
    val detailCodes: List<String>

    /** E1. Read in flight: nothing below it is evaluable. */
    data object ReadPending : ProfileGateState {
        override val detailCodes = listOf(ProfileGateCodes.READ_PENDING)
    }

    /** E2 to E4. The store could not prove versions and events; never shown as missing or unconfirmed. */
    data class Unreadable(val reason: ProfileFailure) : ProfileGateState {
        override val detailCodes: List<String> get() = listOf(reason.code)
    }

    /** E5. Absence proven by a successful read with no version and no event. */
    data class Missing(val record: ProfileRecord) : ProfileGateState {
        init { require(record.latest == null && record.events.isEmpty()) }
        override val detailCodes = listOf(ProfileGateCodes.HISTORY_MISSING)
    }

    /** E6 to E11. Only the latest version is evaluated and only its own confirmation counts. */
    data class Evaluated(
        val record: ProfileRecord,
        val completeness: ProfileCompleteness,
        val confirmation: VersionConfirmation,
    ) : ProfileGateState {
        val latest: ProfileVersion = requireNotNull(record.latest)

        init { require(confirmation == record.confirmationOf(latest.version)) }

        val gaps: List<CompletenessGap> get() = (completeness as? ProfileCompleteness.Incomplete)?.gaps.orEmpty()
        val superseded: List<ConfirmationEvent> get() = record.supersededConfirmations
        val activeConfirmation: ConfirmationEvent? get() = (confirmation as? VersionConfirmation.Active)?.confirmation

        /** Data preconditions only; the UI still enforces "no unsaved editor changes" (ADR 0014, section 5.4). */
        val canConfirm: Boolean get() = completeness == ProfileCompleteness.Complete && confirmation !is VersionConfirmation.Active

        /** Revoking needs an active confirmation of the latest version, not completeness (E11). */
        val canRevoke: Boolean get() = confirmation is VersionConfirmation.Active

        override val detailCodes: List<String>
            get() = buildList {
                val complete = completeness == ProfileCompleteness.Complete
                if (gaps.any { it !is CompletenessGap.TimeZoneUnrecognized }) add(ProfileGateCodes.INCOMPLETE)
                if (gaps.any { it is CompletenessGap.TimeZoneUnrecognized }) add(ProfileGateCodes.TIME_ZONE_UNRECOGNIZED)
                when (confirmation) {
                    VersionConfirmation.Unconfirmed -> {
                        if (complete) add(ProfileGateCodes.CONFIRMATION_MISSING)
                        if (superseded.isNotEmpty()) add(ProfileGateCodes.CONFIRMATION_SUPERSEDED)
                    }
                    is VersionConfirmation.Active -> if (complete) add(ProfileGateCodes.NOT_APPROVED)
                    is VersionConfirmation.Revoked -> add(ProfileGateCodes.CONFIRMATION_REVOKED)
                }
            }
    }

    companion object {
        /** Completeness is checked with the platform rules at the moment of evaluation. */
        fun of(read: ProfileRecordRead, zones: TimeZoneRules): ProfileGateState = when (read) {
            is ProfileRecordRead.Failed -> Unreadable(read.reason)
            is ProfileRecordRead.Loaded -> of(read.record, zones)
        }

        fun of(record: ProfileRecord, zones: TimeZoneRules): ProfileGateState {
            val latest = record.latest ?: return Missing(record)
            return Evaluated(record, latest.completeness(zones), record.confirmationOf(latest.version))
        }
    }
}

/** Port result: [Recorded] only after commit, with the current record derived from the same validated history. */
sealed interface ConfirmationWrite {
    data class Recorded(val event: ConfirmationEvent, val replayed: Boolean, val record: ProfileRecord) : ConfirmationWrite {
        init { require(record.event(event.seq) == event) }
    }
    data class Failed(val reason: ProfileFailure) : ConfirmationWrite
}

/**
 * Use-case result. [Recorded.replayed] = true means "that operation was registered at the time", not "it is active
 * now"; [Recorded.state] is always the current state (ADR 0014, section 7.4).
 */
sealed interface ConfirmationOutcome {
    val allowsCalculation: Boolean get() = false
    val allowsTreatment: Boolean get() = false

    data class Recorded(val event: ConfirmationEvent, val replayed: Boolean, val state: ProfileGateState) : ConfirmationOutcome {
        /** True only for a confirmation that is still the active one of the latest version. */
        val confirmationActiveNow: Boolean
            get() = event.kind == ConfirmationEventKind.CONFIRM && (state as? ProfileGateState.Evaluated)?.activeConfirmation == event
    }

    data class Rejected(val reason: ProfileFailure) : ConfirmationOutcome
}

/** How a pending operation kept across recreation is resolved (ADR 0014, section 9.3). */
sealed interface PendingResolution {
    val state: ProfileGateState

    /** Read not proven: keep the operation, do not retry, report neither success nor failure. */
    data class Undetermined(override val state: ProfileGateState) : PendingResolution

    /** The operation committed before recreation, possibly already superseded by later events. */
    data class Recorded(val event: ConfirmationEvent, override val state: ProfileGateState) : PendingResolution

    /** The review is closed with a visible reason and the pending operation is discarded. */
    data class Closed(val reason: ProfileFailure, override val state: ProfileGateState) : PendingResolution

    /** Nothing changed: the review stays open and may retry with the same operation identity. */
    data class Open(override val state: ProfileGateState) : PendingResolution
}

object PendingConfirmations {
    fun resolve(pending: ConfirmationRequest, state: ProfileGateState): PendingResolution {
        val record = when (state) {
            is ProfileGateState.Missing -> state.record
            is ProfileGateState.Evaluated -> state.record
            ProfileGateState.ReadPending, is ProfileGateState.Unreadable -> return PendingResolution.Undetermined(state)
        }
        // 1. Resolve by identity first: a committed operation is never shown as a conflict or a failure.
        record.eventByOperation(pending.operationId)?.let { stored ->
            return if (stored.sameIntention(pending)) PendingResolution.Recorded(stored, state)
            else PendingResolution.Closed(ProfileFailure.CONFIRMATION_OPERATION_MISMATCH, state)
        }
        // 2. Not recorded: only now compare what was reviewed with what is stored.
        val latest = record.latest
        when (pending) {
            is ConfirmationRequest.Confirm -> if (latest == null || latest.version != pending.profileVersion ||
                latest.contentSha256 != pending.contentSha256) {
                return PendingResolution.Closed(ProfileFailure.CONFIRMATION_STALE_VERSION, state)
            }
            is ConfirmationRequest.Revoke -> {
                val target = record.event(pending.revokesSeq)
                if (target == null || target.kind != ConfirmationEventKind.CONFIRM) {
                    return PendingResolution.Closed(ProfileFailure.CONFIRMATION_NOT_ACTIVE, state)
                }
                if (latest == null || target.profileVersion != latest.version) {
                    return PendingResolution.Closed(ProfileFailure.CONFIRMATION_STALE_VERSION, state)
                }
            }
        }
        if (pending.observedEventSeq != record.lastEventSeq) {
            return PendingResolution.Closed(ProfileFailure.CONFIRMATION_STATE_CHANGED, state)
        }
        return PendingResolution.Open(state)
    }
}
