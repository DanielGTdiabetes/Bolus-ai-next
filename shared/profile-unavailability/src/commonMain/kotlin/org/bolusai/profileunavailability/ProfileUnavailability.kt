package org.bolusai.profileunavailability

import org.bolusai.engine.InputKind
import org.bolusai.engine.InputUnavailability
import org.bolusai.engine.InputUnavailabilityReport
import org.bolusai.engine.UnavailabilityReasonV2
import org.bolusai.profile.ClinicalEligibility
import org.bolusai.profile.CompletenessGap
import org.bolusai.profile.ProfileFailure
import org.bolusai.profile.ProfileGateCodes
import org.bolusai.profile.ProfileGateState
import org.bolusai.profile.VersionConfirmation

/** Stable rejection identifiers of the translator, carried as the [IllegalArgumentException] message. */
public object ProfileUnavailabilityErrors {
    /**
     * [ProfileGateState.Unreadable] carries a failure that is not a read failure (E2 to E4 of ADR 0014). Operation
     * errors such as a save failure or a write conflict are never input states (ADR 0014, section 6.2), so the
     * translator refuses them instead of inventing a cause.
     */
    public const val UNREADABLE_REASON_NOT_SUPPORTED: String = "profile_unavailability.unreadable_reason_not_supported"
}

/**
 * Translates a received [ProfileGateState] into contract v2 causes (ADR 0015, sections 6.1 and 6.2). It derives the
 * causes from the dimensions of the state (read, completeness, confirmation and eligibility), not only from
 * [ProfileGateState.detailCodes]. It does not read storage, add clinical rules, declare `expired` or produce
 * `conflicting`; it never authorizes calculation or treatment.
 */
public object ProfileUnavailability {
    @Throws(IllegalArgumentException::class)
    public fun report(state: ProfileGateState): InputUnavailabilityReport = InputUnavailabilityReport(causes(state))

    /** The causes before merging, in dimension order. */
    private fun causes(state: ProfileGateState): List<InputUnavailability> = buildList {
        when (state) {
            ProfileGateState.ReadPending -> add(cause(UnavailabilityReasonV2.UNKNOWN, ProfileGateCodes.READ_PENDING))
            is ProfileGateState.Unreadable -> add(unreadable(state.reason))
            is ProfileGateState.Missing -> add(cause(UnavailabilityReasonV2.MISSING, ProfileGateCodes.HISTORY_MISSING))
            is ProfileGateState.Evaluated -> addAll(evaluated(state))
        }
        // Exhaustive on purpose: a future eligibility value must revisit this translation.
        when (state.eligibility) {
            ClinicalEligibility.NOT_APPROVED ->
                add(cause(UnavailabilityReasonV2.POLICY_NOT_APPROVED, ProfileGateCodes.NOT_APPROVED))
        }
    }

    private fun unreadable(failure: ProfileFailure): InputUnavailability = when (failure) {
        ProfileFailure.READ_FAILED, ProfileFailure.CORRUPT_STORAGE -> cause(UnavailabilityReasonV2.PERSISTENCE_FAILED, failure.code)
        ProfileFailure.INVALID_RECORD -> cause(UnavailabilityReasonV2.INVALID, failure.code)
        ProfileFailure.UNSUPPORTED_SCHEMA -> cause(UnavailabilityReasonV2.PARSE_FAILED, failure.code)
        else -> throw IllegalArgumentException(ProfileUnavailabilityErrors.UNREADABLE_REASON_NOT_SUPPORTED)
    }

    /** Completeness and confirmation accumulate independently; no cause replaces another. */
    private fun evaluated(state: ProfileGateState.Evaluated): List<InputUnavailability> = buildList {
        val gaps = state.gaps
        if (gaps.any { it !is CompletenessGap.TimeZoneUnrecognized }) {
            add(cause(UnavailabilityReasonV2.INCOMPLETE, ProfileGateCodes.INCOMPLETE))
        }
        if (gaps.any { it is CompletenessGap.TimeZoneUnrecognized }) {
            add(cause(UnavailabilityReasonV2.INVALID, ProfileGateCodes.TIME_ZONE_UNRECOGNIZED))
        }
        val superseded = if (state.superseded.isEmpty()) emptyList() else listOf(ProfileGateCodes.CONFIRMATION_SUPERSEDED)
        when (state.confirmation) {
            VersionConfirmation.Unconfirmed ->
                add(cause(UnavailabilityReasonV2.UNCONFIRMED, ProfileGateCodes.CONFIRMATION_MISSING, *superseded.toTypedArray()))
            is VersionConfirmation.Revoked ->
                add(cause(UnavailabilityReasonV2.UNCONFIRMED, ProfileGateCodes.CONFIRMATION_REVOKED, *superseded.toTypedArray()))
            is VersionConfirmation.Active -> Unit
        }
    }

    private fun cause(reason: UnavailabilityReasonV2, vararg details: String): InputUnavailability =
        InputUnavailability(InputKind.PROFILE, reason, details.toList())
}
