package org.bolusai.next

import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput
import org.bolusai.next.application.ReadOverview
import org.bolusai.next.application.UnavailableOverview
import org.bolusai.next.glucose.PendingDexcomSource
import org.bolusai.next.glucose.ReadLocalGlucoseStatus
import org.bolusai.next.navigation.Destination
import org.bolusai.next.ui.BlockingDetails
import org.bolusai.next.ui.ProfileUnavailabilityBlock
import org.bolusai.profile.*
import org.bolusai.profileunavailability.ProfileUnavailability
import org.junit.Assert.*
import org.junit.Test

/** ADR 0017, B3, B5 and B10: the combined details of Bolo and Diagnóstico. Synthetic values only. */
class BlockingDetailsTest {
    private val policy = "input.profile.policy_not_approved [profile.not_approved_for_calculation]"
    private val pending = "input.profile.unknown [profile.read.pending]"
    private val rejected = "profile_unavailability.unreadable_reason_not_supported"
    private val zones = TimeZoneRules { it == "Europe/Madrid" }
    private val overview = ReadOverview(ReadLocalGlucoseStatus(PendingDexcomSource)).execute()

    private fun content(sensitivity: ProfileValue = ProfileValue.Entered(CanonicalDecimal("40"))) = ProfileContent(
        ProfileCatalog.CURRENT_SCHEMA, Setting.Declared(GlucoseUnit.MG_DL), Setting.Declared(ProfileTimeZone("Europe/Madrid")),
        listOf(ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, ProfileValue.Entered(CanonicalDecimal("10"))),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, sensitivity),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, ProfileValue.Entered(CanonicalDecimal("110")))))

    private fun version(body: ProfileContent) = (ProfileWritePolicy.evaluate(ProfileWrite(0, body, ProfileOrigin.MANUAL, null),
        1_790_000_000_000, "test/synthetic", null, null, null) as ProfileWriteDecision.Insert).version

    private fun state(versions: List<ProfileVersion>, events: List<ConfirmationEvent> = emptyList(),
                      rules: TimeZoneRules = zones) = ProfileGateState.of(ProfileRecord.validate(versions, events), rules)

    private fun confirm(v: ProfileVersion): ConfirmationEvent {
        val record = (ProfileRecord.validate(listOf(v), emptyList()) as ProfileRecordRead.Loaded).record
        val request = ConfirmationRequest.Confirm(OperationId("00000000-0000-4000-8000-000000000001"), v.version, v.contentSha256, 0)
        return (ConfirmationPolicy.evaluate(request, 1_790_000_000_001, "test/synthetic", record, zones)
            as ConfirmationDecision.Insert).event
    }

    private fun revoke(v: ProfileVersion, confirmation: ConfirmationEvent): ConfirmationEvent {
        val record = (ProfileRecord.validate(listOf(v), listOf(confirmation)) as ProfileRecordRead.Loaded).record
        val request = ConfirmationRequest.Revoke(OperationId("00000000-0000-4000-8000-000000000002"), confirmation.seq,
            confirmation.seq)
        return (ConfirmationPolicy.evaluate(request, 1_790_000_000_002, "test/synthetic", record, zones)
            as ConfirmationDecision.Insert).event
    }

    /** E1 to E11 as the profile screen proves them, plus the states without a proven read. */
    private fun everyState(): List<ProfileGateState?> {
        val complete = version(content())
        val confirmed = listOf(confirm(complete))
        return listOf(
            null,
            ProfileGateState.ReadPending,
            ProfileGateState.Unreadable(ProfileFailure.READ_FAILED),
            ProfileGateState.Unreadable(ProfileFailure.CORRUPT_STORAGE),
            ProfileGateState.Unreadable(ProfileFailure.INVALID_RECORD),
            ProfileGateState.Unreadable(ProfileFailure.UNSUPPORTED_SCHEMA),
            state(emptyList()),
            state(listOf(version(content(sensitivity = ProfileValue.NotConfigured)))),
            state(listOf(complete)),
            state(listOf(complete), confirmed),
            state(listOf(complete), confirmed + revoke(complete, confirmed.single())),
            state(listOf(complete), confirmed, TimeZoneRules { false }),
        )
    }

    private fun lines(gate: ProfileGateState?, base: UnavailableOverview = overview) =
        BlockingDetails.lines(base, ProfileUnavailabilityBlock.of(gate))

    @Test fun onlyBoloAndDiagnosticsShowTheBlockAndMayStartAProfileRead() {
        assertEquals(setOf(Destination.BOLUS, Destination.MANUAL, Destination.OFFLINE_BOLUS, Destination.DIAGNOSTICS),
            BlockingDetails.destinations)
    }

    @Test fun beforeTheFirstReadTheBlockShowsThePendingReadAndThePolicy() {
        assertEquals(listOf("input.glucose.policy_not_approved", "input.iob.unknown", "input.meal.missing", policy, pending),
            lines(null))
        assertEquals(lines(null), lines(ProfileGateState.ReadPending))
    }

    @Test fun oneReportWithLiftedInputsAndExactlyTheTranslatorProfileCauses() {
        everyState().forEach { gate ->
            val shown = lines(gate)
            val profileLines = ProfileUnavailability.report(gate ?: ProfileGateState.ReadPending).entries
                .map { ProfileUnavailabilityBlock.line(it) }
            assertEquals(profileLines, shown.filter { it.startsWith("input.profile.") })
            assertEquals(listOf("input.glucose.policy_not_approved", "input.iob.unknown", "input.meal.missing"),
                shown.filterNot { it.startsWith("input.profile.") })
            // Ordered by code, as every report is: technical order, never a priority.
            assertEquals(shown.map { it.substringBefore(' ') }.sorted(), shown.map { it.substringBefore(' ') })
            // The static v1 profile cause is contained in every valid report and never duplicated.
            assertTrue(policy in shown)
            assertEquals(1, shown.count { it.startsWith(overview.profile.code) })
            assertEquals(shown.size, shown.toSet().size)
            assertFalse(overview.allowsCalculation || overview.allowsTreatment)
            gate?.let { assertFalse(it.allowsCalculation || it.allowsTreatment) }
        }
    }

    @Test fun reportDetailsUseTheProfileScreenFormat() {
        assertEquals(listOf("input.glucose.policy_not_approved", "input.iob.unknown", "input.meal.missing",
            "input.profile.persistence_failed [profile.storage.read_failed]", policy),
            lines(ProfileGateState.Unreadable(ProfileFailure.READ_FAILED)))
        val v = version(content())
        assertEquals(listOf("input.glucose.policy_not_approved", "input.iob.unknown", "input.meal.missing", policy),
            lines(state(listOf(v), listOf(confirm(v)))))
    }

    @Test fun everyGlucoseReasonCombinesWithEveryProfileStateWithoutRejection() {
        UnavailabilityReason.entries.forEach { reason ->
            val base = overview.copy(glucose = UnavailableInput(InputKind.GLUCOSE, reason))
            everyState().forEach { gate ->
                val shown = lines(gate, base)
                assertEquals(1, shown.count { it == "input.glucose.${reason.code}" })
                assertTrue(policy in shown)
            }
        }
    }

    @Test fun theKnownRejectionShowsTheIdentifierWithoutFabricatingAProfileCause() {
        val shown = lines(ProfileGateState.Unreadable(ProfileFailure.SAVE_FAILED))
        assertEquals(listOf("input.glucose.policy_not_approved", "input.iob.unknown", "input.meal.missing", rejected), shown)
        assertTrue(shown.none { it.startsWith("input.profile.") })
        assertFalse(overview.allowsCalculation || overview.allowsTreatment)
    }

    @Test fun anotherTranslatorFailurePropagatesInsteadOfBecomingABlockLine() {
        val failure = IllegalArgumentException("synthetic")
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            BlockingDetails.lines(overview, ProfileUnavailabilityBlock.of(ProfileGateState.ReadPending) { throw failure })
        })
    }
}
