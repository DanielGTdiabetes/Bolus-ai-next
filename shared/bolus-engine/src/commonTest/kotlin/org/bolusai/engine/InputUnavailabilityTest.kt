package org.bolusai.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR 0015, sections C.1, C.2 and 8. Synthetic technical identifiers only, no clinical values. */
class InputUnavailabilityTest {
    private fun rejected(expected: String, build: () -> Any) {
        assertEquals(expected, assertFailsWith<IllegalArgumentException> { build() }.message)
    }

    private fun profile(reason: UnavailabilityReasonV2, vararg details: String) =
        InputUnavailability(InputKind.PROFILE, reason, details.toList())

    @Test
    fun v1CodesKeepTheirIdentifiersAndOnlyUnconfirmedIsAdded(): Unit {
        assertEquals(
            UnavailabilityReason.entries.map { it.code } + "unconfirmed",
            UnavailabilityReasonV2.entries.map { it.code },
        )
        UnavailabilityReason.entries.forEach { assertEquals(it.code, UnavailabilityReasonV2.from(it).code) }
        assertEquals(UnavailabilityReason.entries.size, UnavailabilityReason.entries.map { UnavailabilityReasonV2.from(it) }.toSet().size)
        assertEquals("input.profile.unconfirmed", profile(UnavailabilityReasonV2.UNCONFIRMED).code)
        assertEquals(2, profile(UnavailabilityReasonV2.MISSING).contractVersion)
    }

    @Test
    fun admissibilityTableAdmitsExactly53CombinationsAndRejectsTheOther3(): Unit {
        val admitted = mutableListOf<String>()
        val rejectedCodes = mutableListOf<String>()
        InputKind.entries.forEach { input ->
            UnavailabilityReasonV2.entries.forEach { reason ->
                val code = "input.${input.code}.${reason.code}"
                if (reason.isAdmittedFor(input)) {
                    val cause = InputUnavailability(input, reason, emptyList())
                    assertEquals(code, cause.code)
                    admitted += code
                } else {
                    rejected(InputUnavailabilityErrors.REASON_NOT_ADMITTED) { InputUnavailability(input, reason, emptyList()) }
                    rejectedCodes += code
                }
            }
        }
        assertEquals(53, admitted.size)
        assertEquals(53, admitted.toSet().size)
        assertEquals(listOf("input.glucose.unconfirmed", "input.iob.unconfirmed", "input.meal.unconfirmed"), rejectedCodes)
        assertEquals(AdmissibilityTable.expectedAdmitted, admitted.sorted())
    }

    @Test
    fun admissionIsCheckedBeforeTheDetailEvenWithAValidDetail(): Unit {
        rejected(InputUnavailabilityErrors.REASON_NOT_ADMITTED) {
            InputUnavailability(InputKind.GLUCOSE, UnavailabilityReasonV2.UNCONFIRMED, listOf("glucose.anything"))
        }
    }

    @Test
    fun elevationIsTotalOverThe52V1CombinationsWithEmptyDetail(): Unit {
        val v1 = InputKind.entries.flatMap { input -> UnavailabilityReason.entries.map { UnavailableInput(input, it) } }
        assertEquals(52, v1.size)
        v1.forEach { cause ->
            val elevated = InputUnavailability.from(cause)
            assertEquals(cause.code, elevated.code)
            assertEquals(cause.input, elevated.input)
            assertEquals(cause.reason.code, elevated.reason.code)
            assertEquals(emptyList(), elevated.details)
            assertEquals(2, elevated.contractVersion)
        }
    }

    @Test
    fun detailLengthLimitsAreOneTo128Characters(): Unit {
        val prefix = "profile."
        val longest = prefix + "a".repeat(InputUnavailability.MAX_DETAIL_LENGTH - prefix.length)
        assertEquals(128, longest.length)
        assertEquals(listOf(longest), profile(UnavailabilityReasonV2.UNKNOWN, longest).details)
        rejected(InputUnavailabilityErrors.DETAIL_MALFORMED) { profile(UnavailabilityReasonV2.UNKNOWN, longest + "a") }
        rejected(InputUnavailabilityErrors.DETAIL_MALFORMED) { profile(UnavailabilityReasonV2.UNKNOWN, "") }
        // The shortest well-formed detail of each input still needs one segment.
        assertEquals(listOf("iob.a"), InputUnavailability(InputKind.IOB, UnavailabilityReasonV2.UNKNOWN, listOf("iob.a")).details)
    }

    @Test
    fun grammarRejectsEveryMalformedDetailWithItsStableIdentifier(): Unit {
        listOf(
            "Profile.x", "profile.X", "profile", "profile.", "profile..x", ".profile.x", "profile.x.",
            "profile.1x", "profile._x", "profile.x-y", "profile.x y", " profile.x", "profile.x\n",
            "profile.café", "profile.ñ", "perfil sin confirmar", "profile.120",
        ).forEach { detail ->
            rejected(InputUnavailabilityErrors.DETAIL_MALFORMED) { profile(UnavailabilityReasonV2.UNCONFIRMED, detail) }
        }
        listOf("profile.confirmation.revoked", "profile.storage.read_failed", "profile.a1_b2.c").forEach {
            assertEquals(listOf(it), profile(UnavailabilityReasonV2.UNCONFIRMED, it).details)
        }
    }

    @Test
    fun detailMustBelongToTheInputOfItsCause(): Unit {
        listOf("glucose.x", "iob.x", "meal.x", "profiles.x", "prof.x").forEach { detail ->
            rejected(InputUnavailabilityErrors.DETAIL_FOREIGN_NAMESPACE) { profile(UnavailabilityReasonV2.INVALID, detail) }
        }
        rejected(InputUnavailabilityErrors.DETAIL_FOREIGN_NAMESPACE) {
            InputUnavailability(InputKind.GLUCOSE, UnavailabilityReasonV2.MISSING, listOf("profile.history.missing"))
        }
    }

    @Test
    fun sixteenDistinctDetailsAreAcceptedAndSeventeenRejected(): Unit {
        val sixteen = (1..16).map { "profile.d$it" }
        assertEquals(sixteen.sorted(), profile(UnavailabilityReasonV2.INVALID, *sixteen.toTypedArray()).details)
        rejected(InputUnavailabilityErrors.TOO_MANY_DETAILS) {
            profile(UnavailabilityReasonV2.INVALID, *(sixteen + "profile.d17").toTypedArray())
        }
        // The limit applies after removing duplicates.
        val repeated = sixteen + sixteen
        assertEquals(16, profile(UnavailabilityReasonV2.INVALID, *repeated.toTypedArray()).details.size)
    }

    @Test
    fun rejectionDoesNotDependOnProducerOrder(): Unit {
        val details = listOf("glucose.x", "Profile.x")
        rejected(InputUnavailabilityErrors.DETAIL_MALFORMED) { profile(UnavailabilityReasonV2.INVALID, *details.toTypedArray()) }
        rejected(InputUnavailabilityErrors.DETAIL_MALFORMED) {
            profile(UnavailabilityReasonV2.INVALID, *details.reversed().toTypedArray())
        }
    }

    @Test
    fun duplicatesAreRemovedAndOrderIsDeterministic(): Unit {
        val a = profile(UnavailabilityReasonV2.UNCONFIRMED, "profile.confirmation.superseded", "profile.confirmation.missing",
            "profile.confirmation.superseded")
        val b = profile(UnavailabilityReasonV2.UNCONFIRMED, "profile.confirmation.missing", "profile.confirmation.superseded")
        assertEquals(listOf("profile.confirmation.missing", "profile.confirmation.superseded"), a.details)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, profile(UnavailabilityReasonV2.UNCONFIRMED, "profile.confirmation.missing"))
        assertNotEquals(profile(UnavailabilityReasonV2.INVALID), profile(UnavailabilityReasonV2.MISSING))
        assertNotEquals(
            InputUnavailability(InputKind.IOB, UnavailabilityReasonV2.UNKNOWN, emptyList()),
            InputUnavailability(InputKind.MEAL, UnavailabilityReasonV2.UNKNOWN, emptyList()),
        )
    }

    @Test
    fun mutatingTheProducerListAfterConstructionDoesNotChangeTheCause(): Unit {
        val producer = mutableListOf("profile.confirmation.revoked")
        val cause = InputUnavailability(InputKind.PROFILE, UnavailabilityReasonV2.UNCONFIRMED, producer)
        producer.clear()
        producer += "Not.Valid"
        assertEquals(listOf("profile.confirmation.revoked"), cause.details)
    }

    @Test
    fun mutatingTheReturnedDetailsDoesNotChangeTheCause(): Unit {
        val cause = profile(UnavailabilityReasonV2.UNCONFIRMED, "profile.confirmation.revoked")
        val exposed = cause.details
        try {
            (exposed as MutableList<String>).add("Not.Valid")
        } catch (_: ClassCastException) {
        } catch (_: UnsupportedOperationException) {
        }
        assertEquals(listOf("profile.confirmation.revoked"), cause.details)
        assertTrue(cause.details !== cause.details)
    }

    @Test
    fun v1ContractIsUntouched(): Unit {
        val v1 = UnavailableInput(InputKind.PROFILE, UnavailabilityReason.MISSING)
        assertEquals(1, v1.contractVersion)
        assertEquals("input.profile.missing", v1.code)
        assertEquals(13, UnavailabilityReason.entries.size)
    }
}

/** Expected admitted combinations of table C.1, kept independent of [UnavailabilityReasonV2.isAdmittedFor]. */
internal object AdmissibilityTable {
    private val v1Reasons = listOf(
        "missing", "invalid", "expired", "unknown", "incomplete", "conflicting", "permission_denied",
        "source_unavailable", "authentication_failed", "clock_anomaly", "parse_failed", "persistence_failed",
        "policy_not_approved",
    )
    val expectedAdmitted: List<String> =
        (listOf("glucose", "profile", "iob", "meal").flatMap { input -> v1Reasons.map { "input.$input.$it" } } +
            "input.profile.unconfirmed").sorted()
}
