package org.bolusai.engine

/** Identifies an input without carrying clinical values or provider DTOs. */
public enum class InputKind(public val code: String) {
    GLUCOSE("glucose"),
    PROFILE("profile"),
    IOB("iob"),
    MEAL("meal"),
}

/**
 * Reported causes, not validation policies. The producer must establish the
 * cause; this contract does not decide freshness, completeness or validity.
 * Codes are stable identifiers independent of translated UI text and ordinals.
 */
public enum class UnavailabilityReason(public val code: String) {
    MISSING("missing"),
    INVALID("invalid"),
    EXPIRED("expired"),
    UNKNOWN("unknown"),
    INCOMPLETE("incomplete"),
    CONFLICTING("conflicting"),
    PERMISSION_DENIED("permission_denied"),
    SOURCE_UNAVAILABLE("source_unavailable"),
    AUTHENTICATION_FAILED("authentication_failed"),
    CLOCK_ANOMALY("clock_anomaly"),
    PARSE_FAILED("parse_failed"),
    PERSISTENCE_FAILED("persistence_failed"),
    POLICY_NOT_APPROVED("policy_not_approved"),
}

/**
 * A versioned, value-free report for an input that cannot support a calculation.
 * Both fields are mandatory: absence must never become a default clinical value.
 * This is an in-memory contract, not a serialized or persisted clinical record.
 */
public data class UnavailableInput(
    public val input: InputKind,
    public val reason: UnavailabilityReason,
) {
    public val contractVersion: Int get() = 1

    public val code: String get() = "input.${input.code}.${reason.code}"
}
