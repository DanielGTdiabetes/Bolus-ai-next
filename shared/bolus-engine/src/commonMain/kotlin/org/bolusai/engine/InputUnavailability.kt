package org.bolusai.engine

/**
 * Reasons of contract version 2 (ADR 0015, option C). The thirteen v1 reasons keep their code and meaning; the only
 * addition is [UNCONFIRMED]. Like v1, a reason is a cause established by its producer, never a validation policy.
 */
public enum class UnavailabilityReasonV2(public val code: String) {
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

    /** Read and data are proven, but the latest version has no active confirmation. Admitted only for the profile. */
    UNCONFIRMED("unconfirmed"),
    ;

    /** Admissibility table C.1 of ADR 0015: 53 admitted combinations out of 56. */
    public fun isAdmittedFor(input: InputKind): Boolean = when (this) {
        UNCONFIRMED -> input == InputKind.PROFILE
        else -> true
    }

    public companion object {
        /** Total elevation of a v1 reason: same code, same meaning. Never fails. */
        public fun from(reason: UnavailabilityReason): UnavailabilityReasonV2 = when (reason) {
            UnavailabilityReason.MISSING -> MISSING
            UnavailabilityReason.INVALID -> INVALID
            UnavailabilityReason.EXPIRED -> EXPIRED
            UnavailabilityReason.UNKNOWN -> UNKNOWN
            UnavailabilityReason.INCOMPLETE -> INCOMPLETE
            UnavailabilityReason.CONFLICTING -> CONFLICTING
            UnavailabilityReason.PERMISSION_DENIED -> PERMISSION_DENIED
            UnavailabilityReason.SOURCE_UNAVAILABLE -> SOURCE_UNAVAILABLE
            UnavailabilityReason.AUTHENTICATION_FAILED -> AUTHENTICATION_FAILED
            UnavailabilityReason.CLOCK_ANOMALY -> CLOCK_ANOMALY
            UnavailabilityReason.PARSE_FAILED -> PARSE_FAILED
            UnavailabilityReason.PERSISTENCE_FAILED -> PERSISTENCE_FAILED
            UnavailabilityReason.POLICY_NOT_APPROVED -> POLICY_NOT_APPROVED
        }
    }
}

/** Stable rejection identifiers of contract version 2, carried as the [IllegalArgumentException] message. */
public object InputUnavailabilityErrors {
    public const val REASON_NOT_ADMITTED: String = "input_unavailability.reason_not_admitted"
    public const val DETAIL_MALFORMED: String = "input_unavailability.detail_malformed"
    public const val DETAIL_FOREIGN_NAMESPACE: String = "input_unavailability.detail_foreign_namespace"
    public const val TOO_MANY_DETAILS: String = "input_unavailability.too_many_details"
    public const val REPORT_EMPTY: String = "input_unavailability_report.empty"
}

/**
 * One cause of contract version 2: input, general reason and stable domain detail codes (ADR 0015, sections C.1 and
 * C.2). The detail never replaces the reason. Values are technical identifiers, never clinical values or UI text.
 *
 * The constructor copies [details] before validating, removes identical codes and sorts them; only that copy is kept.
 * Equality and hash code use input, reason and the normalized details. This is deliberately not a data class so the
 * producer's mutable list is never shared. It is an in-memory contract, neither serialized nor persisted.
 */
public class InputUnavailability @Throws(IllegalArgumentException::class) public constructor(
    input: InputKind,
    reason: UnavailabilityReasonV2,
    details: List<String>,
) {
    public val input: InputKind = input
    public val reason: UnavailabilityReasonV2 = reason
    private val normalized: List<String> = normalize(input, reason, details.toList())

    public val contractVersion: Int get() = CONTRACT_VERSION

    /** Same shape as v1: `input.<input>.<reason>`. */
    public val code: String get() = "input.${input.code}.${reason.code}"

    /** Returns a new copy on every access so callers cannot rewrite the cause. */
    public val details: List<String> get() = normalized.toList()

    override fun equals(other: Any?): Boolean =
        other is InputUnavailability && other.input == input && other.reason == reason && other.normalized == normalized

    override fun hashCode(): Int = 31 * (31 * input.hashCode() + reason.hashCode()) + normalized.hashCode()

    override fun toString(): String = "InputUnavailability(code=$code, details=$normalized)"

    public companion object {
        public const val CONTRACT_VERSION: Int = 2

        /** Technical limits of ADR 0015 decision D10. They are not clinical parameters. */
        public const val MAX_DETAIL_LENGTH: Int = 128
        public const val MAX_DETAILS: Int = 16

        private val DETAIL_GRAMMAR = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

        /** Total elevation of the 52 v1 combinations: same input, reason and code, empty detail. Never fails. */
        public fun from(cause: UnavailableInput): InputUnavailability =
            InputUnavailability(cause.input, UnavailabilityReasonV2.from(cause.reason), emptyList())

        /** True when [detail] satisfies the grammar and length of section C.2, whatever its namespace. */
        public fun isWellFormedDetail(detail: String): Boolean =
            detail.length in 1..MAX_DETAIL_LENGTH && DETAIL_GRAMMAR.matches(detail)

        private fun normalize(input: InputKind, reason: UnavailabilityReasonV2, copy: List<String>): List<String> {
            require(reason.isAdmittedFor(input)) { InputUnavailabilityErrors.REASON_NOT_ADMITTED }
            // Validate in normalized order so the reported identifier does not depend on producer order.
            val unique = copy.distinct().sorted()
            unique.forEach { detail ->
                require(isWellFormedDetail(detail)) { InputUnavailabilityErrors.DETAIL_MALFORMED }
                require(detail.substringBefore('.') == input.code) { InputUnavailabilityErrors.DETAIL_FOREIGN_NAMESPACE }
            }
            require(unique.size <= MAX_DETAILS) { InputUnavailabilityErrors.TOO_MANY_DETAILS }
            return unique
        }
    }
}

/**
 * A non-empty snapshot of v2 causes, without precedence. Causes with the same input and reason are merged into one
 * whose detail is the ordered union of theirs; the union passes the 16-detail limit again and is rejected, never
 * truncated, when it exceeds it. Causes are ordered by stable code, as in v1. This report authorizes nothing.
 */
public class InputUnavailabilityReport @Throws(IllegalArgumentException::class) public constructor(
    causes: List<InputUnavailability>,
) {
    private val snapshot: List<InputUnavailability> = merge(causes.toList())

    public val contractVersion: Int get() = InputUnavailability.CONTRACT_VERSION

    /** Returns a copy so callers cannot mutate the captured causes. */
    public val entries: List<InputUnavailability> get() = snapshot.toList()

    override fun equals(other: Any?): Boolean = other is InputUnavailabilityReport && other.snapshot == snapshot

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = "InputUnavailabilityReport(entries=$snapshot)"

    public companion object {
        /** Total elevation of a v1 report: every cause elevated with empty detail. Never fails. */
        public fun from(report: UnavailableInputReport): InputUnavailabilityReport =
            InputUnavailabilityReport(report.entries.map { InputUnavailability.from(it) })

        private fun merge(copy: List<InputUnavailability>): List<InputUnavailability> {
            require(copy.isNotEmpty()) { InputUnavailabilityErrors.REPORT_EMPTY }
            return copy.groupBy { it.input to it.reason }
                .map { (key, group) -> InputUnavailability(key.first, key.second, group.flatMap { it.details }) }
                .sortedBy { it.code }
        }
    }
}
