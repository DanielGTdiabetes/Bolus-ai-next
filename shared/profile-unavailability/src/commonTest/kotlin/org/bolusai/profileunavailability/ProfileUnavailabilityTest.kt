package org.bolusai.profileunavailability

import org.bolusai.engine.InputKind
import org.bolusai.engine.InputUnavailability
import org.bolusai.engine.InputUnavailabilityReport
import org.bolusai.engine.UnavailabilityReasonV2
import org.bolusai.engine.UnavailabilityReasonV2.INCOMPLETE
import org.bolusai.engine.UnavailabilityReasonV2.INVALID
import org.bolusai.engine.UnavailabilityReasonV2.MISSING
import org.bolusai.engine.UnavailabilityReasonV2.PARSE_FAILED
import org.bolusai.engine.UnavailabilityReasonV2.PERSISTENCE_FAILED
import org.bolusai.engine.UnavailabilityReasonV2.POLICY_NOT_APPROVED
import org.bolusai.engine.UnavailabilityReasonV2.UNCONFIRMED
import org.bolusai.engine.UnavailabilityReasonV2.UNKNOWN
import org.bolusai.profile.CanonicalDecimal
import org.bolusai.profile.CompletenessGap
import org.bolusai.profile.ConfirmationDecision
import org.bolusai.profile.ConfirmationEvent
import org.bolusai.profile.ConfirmationPolicy
import org.bolusai.profile.ConfirmationRequest
import org.bolusai.profile.GlucoseUnit
import org.bolusai.profile.OperationId
import org.bolusai.profile.ParameterSchedule
import org.bolusai.profile.ProfileCatalog
import org.bolusai.profile.ProfileCompleteness
import org.bolusai.profile.ProfileContent
import org.bolusai.profile.ProfileFailure
import org.bolusai.profile.ProfileGateState
import org.bolusai.profile.ProfileOrigin
import org.bolusai.profile.ProfileParameter
import org.bolusai.profile.ProfileRecord
import org.bolusai.profile.ProfileRecordRead
import org.bolusai.profile.ProfileTimeZone
import org.bolusai.profile.ProfileValue
import org.bolusai.profile.ProfileVersion
import org.bolusai.profile.ProfileWrite
import org.bolusai.profile.ProfileWriteDecision
import org.bolusai.profile.ProfileWritePolicy
import org.bolusai.profile.Setting
import org.bolusai.profile.TimeZoneRules
import org.bolusai.profile.VersionConfirmation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR 0015, sections 6.1, 6.2 and 8: exact matrix E1 to E11 with accumulations. Synthetic values only; none of these
 * numbers is a clinical recommendation or default. Histories are built with the shared policies of the profile.
 */
class ProfileUnavailabilityTest {
    private val policy = cause(POLICY_NOT_APPROVED, "profile.not_approved_for_calculation")
    private val translated = mutableListOf<Pair<ProfileGateState, InputUnavailabilityReport>>()

    private fun cause(reason: UnavailabilityReasonV2, vararg details: String) =
        InputUnavailability(InputKind.PROFILE, reason, details.toList())

    private fun translate(state: ProfileGateState): InputUnavailabilityReport =
        ProfileUnavailability.report(state).also { translated += state to it }

    private fun assertCauses(state: ProfileGateState, vararg expected: InputUnavailability) {
        val report = translate(state)
        assertEquals(InputUnavailabilityReport(expected.toList()), report, state.toString())
        // Strict shape: one cause per expected pair, never merged with something unexpected.
        assertEquals(expected.map { it.code }.sorted(), report.entries.map { it.code })
        assertEquals(report, ProfileUnavailability.report(state), "deterministic for the same received state")
    }

    @AfterTest
    fun everyTranslationKeepsTheInvariants() {
        translated.forEach { (state, report) ->
            val codes = report.entries.map { it.code }
            assertEquals(2, report.contractVersion)
            assertTrue(report.entries.all { it.input == InputKind.PROFILE }, codes.toString())
            assertTrue(policy in report.entries, "policy_not_approved in every state: $codes")
            assertFalse("input.profile.expired" in codes, "no approved freshness policy")
            assertFalse("input.profile.conflicting" in codes, "operation conflicts are never input states")
            assertFalse(state.allowsCalculation || state.allowsTreatment)
            // Every domain code the gate already publishes travels in the contract detail.
            val details = report.entries.flatMap { it.details }.toSet()
            assertTrue(details.containsAll(state.detailCodes), "$details misses ${state.detailCodes}")
        }
    }

    // E1 to E5: no completeness or confirmation is evaluated.

    @Test fun e1ReadPending() = assertCauses(ProfileGateState.ReadPending, cause(UNKNOWN, "profile.read.pending"), policy)

    @Test fun e2ReadFailedAndCorruptStorage() {
        assertCauses(ProfileGateState.Unreadable(ProfileFailure.READ_FAILED),
            cause(PERSISTENCE_FAILED, "profile.storage.read_failed"), policy)
        assertCauses(ProfileGateState.Unreadable(ProfileFailure.CORRUPT_STORAGE),
            cause(PERSISTENCE_FAILED, "profile.storage.corrupt"), policy)
    }

    @Test fun e3InvalidHistory() = assertCauses(ProfileGateState.Unreadable(ProfileFailure.INVALID_RECORD),
        cause(INVALID, "profile.storage.invalid_record"), policy)

    @Test fun e4UnsupportedSchema() = assertCauses(ProfileGateState.Unreadable(ProfileFailure.UNSUPPORTED_SCHEMA),
        cause(PARSE_FAILED, "profile.storage.unsupported_schema"), policy)

    @Test fun e3AndE4FromRealValidation() {
        val history = History()
        history.save(content())
        history.confirm()
        history.events[0] = history.events[0].copy(contentSha256 = "0".repeat(64))
        assertCauses(history.state(), cause(INVALID, "profile.storage.invalid_record"), policy)
        history.events[0] = history.events[0].copy(contentSha256 = history.versions[0].contentSha256, contractVersion = 2)
        assertCauses(history.state(), cause(PARSE_FAILED, "profile.storage.unsupported_schema"), policy)
    }

    @Test fun e5ProvenAbsence() = assertCauses(History().state(), cause(MISSING, "profile.history.missing"), policy)

    @Test fun operationErrorsAreNeverTranslatedIntoInputCauses() {
        val readFailures = setOf(ProfileFailure.READ_FAILED, ProfileFailure.CORRUPT_STORAGE, ProfileFailure.INVALID_RECORD,
            ProfileFailure.UNSUPPORTED_SCHEMA)
        ProfileFailure.entries.filterNot { it in readFailures }.forEach { failure ->
            val error = assertFailsWith<IllegalArgumentException>(failure.code) {
                ProfileUnavailability.report(ProfileGateState.Unreadable(failure))
            }
            assertEquals(ProfileUnavailabilityErrors.UNREADABLE_REASON_NOT_SUPPORTED, error.message)
        }
    }

    // E6: incomplete latest version, unconfirmed. Completeness causes accumulate with unconfirmed.

    @Test fun e6OnlyGapsOtherThanTheZone() {
        val history = History()
        history.save(content(sensitivity = ProfileValue.NotConfigured))
        assertCauses(history.state(), cause(INCOMPLETE, "profile.gate.incomplete"),
            cause(UNCONFIRMED, "profile.confirmation.missing"), policy)
    }

    @Test fun e6UndeclaredUnitAndZoneAreGapsOtherThanAnUnrecognizedZone() {
        val history = History()
        history.save(content(unit = Setting.NotConfigured, zone = Setting.NotConfigured,
            sensitivity = ProfileValue.NotConfigured, target = ProfileValue.NotConfigured))
        assertCauses(history.state(), cause(INCOMPLETE, "profile.gate.incomplete"),
            cause(UNCONFIRMED, "profile.confirmation.missing"), policy)
    }

    @Test fun e6OnlyUnrecognizedZone() {
        val history = History()
        history.save(content(zone = zone("Atlantis/Lost")))
        assertCauses(history.state(), cause(INVALID, "profile.gate.time_zone_unrecognized"),
            cause(UNCONFIRMED, "profile.confirmation.missing"), policy)
    }

    @Test fun e6GapsAndUnrecognizedZoneTogether() {
        val history = History()
        history.save(content(zone = zone("Atlantis/Lost"), ratio = ProfileValue.NotConfigured))
        assertCauses(history.state(), cause(INCOMPLETE, "profile.gate.incomplete"),
            cause(INVALID, "profile.gate.time_zone_unrecognized"), cause(UNCONFIRMED, "profile.confirmation.missing"), policy)
    }

    // E7 to E9.

    @Test fun e7CompleteWithoutConfirmation() {
        val history = History()
        history.save(content())
        assertCauses(history.state(), cause(UNCONFIRMED, "profile.confirmation.missing"), policy)
    }

    @Test fun e8EarlierConfirmedLatestUnconfirmedAndComplete() {
        val history = History()
        history.save(content())
        history.confirm()
        history.save(content(ratio = entered("11")))
        assertCauses(history.state(),
            cause(UNCONFIRMED, "profile.confirmation.missing", "profile.confirmation.superseded"), policy)
    }

    @Test fun e8EarlierConfirmedLatestIncomplete() {
        val history = History()
        history.save(content())
        history.confirm()
        history.save(content(ratio = ProfileValue.NotConfigured))
        val state = history.state()
        // The gate omits confirmation.missing for an incomplete version; the translator derives it by dimension (D5).
        assertFalse("profile.confirmation.missing" in state.detailCodes)
        assertCauses(state, cause(INCOMPLETE, "profile.gate.incomplete"),
            cause(UNCONFIRMED, "profile.confirmation.missing", "profile.confirmation.superseded"), policy)
    }

    @Test fun e8EarlierConfirmedLatestWithGapsAndUnrecognizedZone() {
        val history = History()
        history.save(content())
        history.confirm()
        history.save(content(zone = zone("Atlantis/Lost"), target = ProfileValue.NotConfigured))
        assertCauses(history.state(), cause(INCOMPLETE, "profile.gate.incomplete"),
            cause(INVALID, "profile.gate.time_zone_unrecognized"),
            cause(UNCONFIRMED, "profile.confirmation.missing", "profile.confirmation.superseded"), policy)
    }

    @Test fun e9ActiveConfirmationIsOnlyPolicy() {
        val history = History()
        history.save(content())
        history.confirm()
        assertCauses(history.state(), policy)
    }

    @Test fun e9ActiveConfirmationWithSupersededHistoryIsStillOnlyPolicy() {
        val history = History()
        history.save(content())
        history.confirm()
        history.save(content(ratio = entered("11")))
        history.confirm()
        val state = history.state() as ProfileGateState.Evaluated
        assertTrue(state.superseded.isNotEmpty())
        assertCauses(state, policy)
    }

    // E10: revoked confirmation, with and without gaps and superseded confirmations.

    @Test fun e10RevokedComplete() {
        val history = History()
        history.save(content())
        history.revoke(history.confirm())
        assertCauses(history.state(), cause(UNCONFIRMED, "profile.confirmation.revoked"), policy)
    }

    @Test fun e10RevokedCompleteWithSuperseded() {
        val history = History()
        history.save(content())
        history.confirm()
        history.save(content(ratio = entered("11")))
        history.revoke(history.confirm())
        val state = history.state()
        // The gate lists superseded only for "unconfirmed"; the translator derives it from the state (D5).
        assertFalse("profile.confirmation.superseded" in state.detailCodes)
        assertCauses(state, cause(UNCONFIRMED, "profile.confirmation.revoked", "profile.confirmation.superseded"), policy)
    }

    @Test fun e10RevokedWithUnrecognizedZone() {
        val history = History()
        history.save(content())
        history.revoke(history.confirm())
        history.zones.remove("Europe/Madrid")
        assertCauses(history.state(), cause(INVALID, "profile.gate.time_zone_unrecognized"),
            cause(UNCONFIRMED, "profile.confirmation.revoked"), policy)
    }

    @Test fun e10RevokedWithUnrecognizedZoneAndSuperseded() {
        val history = History()
        history.save(content())
        history.confirm()
        history.save(content(ratio = entered("11")))
        history.revoke(history.confirm())
        history.zones.remove("Europe/Madrid")
        assertCauses(history.state(), cause(INVALID, "profile.gate.time_zone_unrecognized"),
            cause(UNCONFIRMED, "profile.confirmation.revoked", "profile.confirmation.superseded"), policy)
    }

    @Test fun e10RevokedWithGapsAndUnrecognizedZoneAndSupersededAccumulate() {
        // A confirmed version was complete when confirmed; any combination is still covered (ADR 0015, 6.1).
        val history = History()
        history.save(content())
        history.confirm()
        history.save(content(ratio = entered("11")))
        history.revoke(history.confirm())
        val record = history.record()
        val state = ProfileGateState.Evaluated(record, ProfileCompleteness.Incomplete(listOf(
            CompletenessGap.TimeZoneUnrecognized("Europe/Madrid"),
            CompletenessGap.ValueNotConfigured(ProfileParameter.CARB_RATIO, 0, ProfileCatalog.MINUTES_PER_DAY),
        )), record.confirmationOf(2))
        assertTrue(state.confirmation is VersionConfirmation.Revoked)
        assertCauses(state, cause(INCOMPLETE, "profile.gate.incomplete"), cause(INVALID, "profile.gate.time_zone_unrecognized"),
            cause(UNCONFIRMED, "profile.confirmation.revoked", "profile.confirmation.superseded"), policy)
    }

    // E11 and accumulations with an active confirmation.

    @Test fun e11ConfirmedWithUnrecognizedZoneKeepsNoUnconfirmed() {
        val history = History()
        history.save(content())
        history.confirm()
        history.zones.remove("Europe/Madrid")
        assertCauses(history.state(), cause(INVALID, "profile.gate.time_zone_unrecognized"), policy)
    }

    @Test fun activeConfirmationWithGapsAndUnrecognizedZoneAccumulatesBoth() {
        val history = History()
        history.save(content())
        history.confirm()
        val record = history.record()
        val state = ProfileGateState.Evaluated(record, ProfileCompleteness.Incomplete(listOf(
            CompletenessGap.UnitNotDeclared, CompletenessGap.TimeZoneUnrecognized("Europe/Madrid"),
        )), record.confirmationOf(1))
        assertTrue(state.confirmation is VersionConfirmation.Active)
        assertCauses(state, cause(INCOMPLETE, "profile.gate.incomplete"), cause(INVALID, "profile.gate.time_zone_unrecognized"), policy)
    }

    @Test fun translationDoesNotDependOnLaterChangesToTheReceivedState() {
        val history = History()
        history.save(content())
        val before = history.state()
        val report = translate(before)
        history.confirm()
        assertEquals(report, ProfileUnavailability.report(before))
        assertEquals(InputUnavailabilityReport(listOf(policy)), translate(history.state()))
    }

    /** Synthetic append-only history built through the shared write and confirmation policies. */
    private class History {
        val versions = mutableListOf<ProfileVersion>()
        val events = mutableListOf<ConfirmationEvent>()
        val zones = mutableSetOf("Europe/Madrid", "UTC")
        private val rules = TimeZoneRules { it in zones }
        private var clock = 1_790_000_000_000
        private var operations = 0

        fun record(): ProfileRecord = (ProfileRecord.validate(versions.toList(), events.toList()) as ProfileRecordRead.Loaded).record

        fun state(): ProfileGateState = ProfileGateState.of(ProfileRecord.validate(versions.toList(), events.toList()), rules)

        fun save(next: ProfileContent): ProfileVersion {
            val latest = versions.maxByOrNull { it.version }
            val decision = ProfileWritePolicy.evaluate(ProfileWrite(latest?.version ?: 0, next, ProfileOrigin.MANUAL, null),
                clock++, WRITER, latest, null, null)
            return (decision as ProfileWriteDecision.Insert).version.also { versions += it }
        }

        fun confirm(): ConfirmationEvent {
            val record = record()
            val latest = requireNotNull(record.latest)
            return append(ConfirmationRequest.Confirm(nextId(), latest.version, latest.contentSha256, record.lastEventSeq), record)
        }

        fun revoke(confirmation: ConfirmationEvent): ConfirmationEvent {
            val record = record()
            return append(ConfirmationRequest.Revoke(nextId(), confirmation.seq, record.lastEventSeq), record)
        }

        private fun append(request: ConfirmationRequest, record: ProfileRecord): ConfirmationEvent {
            val decision = ConfirmationPolicy.evaluate(request, clock++, WRITER, record, rules)
            return (decision as ConfirmationDecision.Insert).event.also { events += it }
        }

        private fun nextId() = OperationId("00000000-0000-4000-8000-" + (++operations).toString().padStart(12, '0'))
    }

    private companion object {
        const val WRITER = "test/synthetic"

        fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))
        fun zone(id: String): Setting<ProfileTimeZone> = Setting.Declared(ProfileTimeZone(id))

        fun content(
            unit: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MG_DL),
            zone: Setting<ProfileTimeZone> = zone("Europe/Madrid"),
            ratio: ProfileValue = entered("10"),
            sensitivity: ProfileValue = entered("40"),
            target: ProfileValue = entered("110"),
        ) = ProfileContent(ProfileCatalog.CURRENT_SCHEMA, unit, zone, listOf(
            ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, ratio),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, sensitivity),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, target),
        ))
    }
}
