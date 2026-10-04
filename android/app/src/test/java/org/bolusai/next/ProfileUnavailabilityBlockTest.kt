package org.bolusai.next

import org.bolusai.engine.InputUnavailabilityReport
import org.bolusai.next.ui.ProfileUnavailabilityBlock
import org.bolusai.profile.*
import org.bolusai.profileunavailability.ProfileUnavailability
import org.junit.Assert.*
import org.junit.Test

/** ADR 0016, A2, A4 and A5: the block derived from the gate state. Synthetic values only, no clinical meaning. */
class ProfileUnavailabilityBlockTest {
    private val policy = "input.profile.policy_not_approved [profile.not_approved_for_calculation]"
    private val pending = listOf(policy, "input.profile.unknown [profile.read.pending]")
    private val zones = TimeZoneRules { it == "Europe/Madrid" }

    private fun lines(gate: ProfileGateState?) = ProfileUnavailabilityBlock.of(gate).lines

    private fun content(sensitivity: ProfileValue = ProfileValue.Entered(CanonicalDecimal("40"))) = ProfileContent(
        ProfileCatalog.CURRENT_SCHEMA, Setting.Declared(GlucoseUnit.MG_DL), Setting.Declared(ProfileTimeZone("Europe/Madrid")),
        listOf(ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, ProfileValue.Entered(CanonicalDecimal("10"))),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, sensitivity),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, ProfileValue.Entered(CanonicalDecimal("110")))))

    private fun version(body: ProfileContent) = (ProfileWritePolicy.evaluate(ProfileWrite(0, body, ProfileOrigin.MANUAL, null),
        1_790_000_000_000, "test/synthetic", null, null, null) as ProfileWriteDecision.Insert).version

    private fun state(versions: List<ProfileVersion>, events: List<ConfirmationEvent> = emptyList()) =
        ProfileGateState.of(ProfileRecord.validate(versions, events), zones)

    private fun confirmed(v: ProfileVersion): ConfirmationEvent {
        val record = (ProfileRecord.validate(listOf(v), emptyList()) as ProfileRecordRead.Loaded).record
        val request = ConfirmationRequest.Confirm(OperationId("00000000-0000-4000-8000-000000000001"), v.version, v.contentSha256, 0)
        return (ConfirmationPolicy.evaluate(request, 1_790_000_000_001, "test/synthetic", record, zones)
            as ConfirmationDecision.Insert).event
    }

    @Test fun anUnprovenStateIsThePendingReadTheScreenShows() {
        assertEquals(pending, lines(null))
        assertEquals(pending, lines(ProfileGateState.ReadPending))
    }

    @Test fun everyStateUsesTheTranslatorReportInItsOrder() {
        listOf<ProfileGateState>(
            ProfileGateState.Unreadable(ProfileFailure.READ_FAILED),
            ProfileGateState.Unreadable(ProfileFailure.CORRUPT_STORAGE),
            ProfileGateState.Unreadable(ProfileFailure.INVALID_RECORD),
            ProfileGateState.Unreadable(ProfileFailure.UNSUPPORTED_SCHEMA),
            state(emptyList()),
            state(listOf(version(content(sensitivity = ProfileValue.NotConfigured)))),
            state(listOf(version(content()))),
        ).forEach { gate ->
            val block = ProfileUnavailabilityBlock.of(gate) as ProfileUnavailabilityBlock.Report
            assertEquals(ProfileUnavailability.report(gate), block.report)
            assertEquals(block.report.entries.map { it.code }, block.lines.map { it.substringBefore(' ') })
            assertTrue(policy in block.lines)
            assertFalse(gate.allowsCalculation || gate.allowsTreatment)
        }
    }

    @Test fun formatIsOneCodePerLineWithItsDetails() {
        // Ordered by code, as the report is: persistence_failed comes before policy_not_approved.
        assertEquals(listOf("input.profile.persistence_failed [profile.storage.read_failed]", policy),
            lines(ProfileGateState.Unreadable(ProfileFailure.READ_FAILED)))
        assertEquals(listOf(policy, "input.profile.unconfirmed [profile.confirmation.missing]"),
            lines(state(listOf(version(content())))))
        val v = version(content())
        assertEquals(listOf(policy), lines(state(listOf(v), listOf(confirmed(v)))))
        // Without detail a cause is its code alone.
        val bare = ProfileUnavailabilityBlock.Report(InputUnavailabilityReport.from(org.bolusai.engine.UnavailableInputReport(
            listOf(org.bolusai.engine.UnavailableInput(org.bolusai.engine.InputKind.PROFILE,
                org.bolusai.engine.UnavailabilityReason.POLICY_NOT_APPROVED)))))
        assertEquals(listOf("input.profile.policy_not_approved"), bare.lines)
    }

    @Test fun theKnownRejectionShowsItsIdentifierWithoutFabricatingAReport() {
        ProfileFailure.entries.filterNot { it in setOf(ProfileFailure.READ_FAILED, ProfileFailure.CORRUPT_STORAGE,
            ProfileFailure.INVALID_RECORD, ProfileFailure.UNSUPPORTED_SCHEMA) }.forEach { failure ->
            val gate = ProfileGateState.Unreadable(failure)
            val block = ProfileUnavailabilityBlock.of(gate)
            assertEquals(ProfileUnavailabilityBlock.Rejected("profile_unavailability.unreadable_reason_not_supported"), block)
            assertEquals(listOf("profile_unavailability.unreadable_reason_not_supported"), block.lines)
            // The received state still blocks: nothing about it changed.
            assertFalse(gate.allowsCalculation || gate.allowsTreatment)
            assertEquals(ProfileVersion.NOT_APPROVED_CODE, gate.blockCode)
        }
    }

    @Test fun anyOtherExceptionPropagatesUnchanged() {
        val other = IllegalArgumentException("input_unavailability.too_many_details")
        assertSame(other, assertThrows(IllegalArgumentException::class.java) {
            ProfileUnavailabilityBlock.of(ProfileGateState.ReadPending) { throw other }
        })
        val unrelated = IllegalArgumentException()
        assertSame(unrelated, assertThrows(IllegalArgumentException::class.java) {
            ProfileUnavailabilityBlock.of(ProfileGateState.ReadPending) { throw unrelated }
        })
        val crash = IllegalStateException("profile_unavailability.unreadable_reason_not_supported")
        assertSame(crash, assertThrows(IllegalStateException::class.java) {
            ProfileUnavailabilityBlock.of(ProfileGateState.ReadPending) { throw crash }
        })
    }

    @Test fun successiveStatesNeverKeepLinesOfAnEarlierState() {
        val failed = ProfileGateState.Unreadable(ProfileFailure.READ_FAILED)
        val unconfirmed = state(listOf(version(content())))
        val sequence = listOf(null, failed, null, unconfirmed, ProfileGateState.Unreadable(ProfileFailure.SAVE_FAILED), null, failed)
        sequence.forEach { gate ->
            val expected = when (gate) {
                null -> pending
                ProfileGateState.Unreadable(ProfileFailure.SAVE_FAILED) -> listOf("profile_unavailability.unreadable_reason_not_supported")
                else -> ProfileUnavailability.report(gate).entries.map { e ->
                    if (e.details.isEmpty()) e.code else "${e.code} [${e.details.joinToString(", ")}]" }
            }
            assertEquals(expected, lines(gate))
        }
    }
}
