package org.bolusai.profile

import kotlin.test.*

/** ADR 0014, sections 2, 5, 7 and 9.3, through the use cases and the shared policy. Synthetic data only. */
class ProfileConfirmationFlowTest {
    private val repository = MemoryProfileRepository()
    private val clock = FixedClock(5_000)
    private val zones = MutableZones("Europe/Madrid", "UTC")
    private val profiles = ClinicalProfiles(repository, clock, "test/synthetic", zones)
    /** A second screen or connection over the same store. */
    private val other = ClinicalProfiles(repository, FixedClock(9_000), "test/other", zones)
    private val ids = SequentialOperationIds()
    private val seen = mutableListOf<ProfileGateState>()

    private fun state(): ProfileGateState = profiles.readState().also { seen.add(it) }
    private fun evaluated(): ProfileGateState.Evaluated = state() as ProfileGateState.Evaluated
    private fun saveNew(next: ProfileContent) = profiles.saved(profiles.edit(profiles.loaded()).with(next))
    private fun confirmNow(id: OperationId = ids.next()) = profiles.confirmRequest(evaluated(), id)
    private fun revokeNow(id: OperationId = ids.next()) = profiles.revokeRequest(evaluated(), id)!!
    private fun recorded(request: ConfirmationRequest) = (profiles.record(request) as ConfirmationOutcome.Recorded).also { seen.add(it.state) }
    private fun rejected(request: ConfirmationRequest, reason: ProfileFailure) {
        val before = repository.events.toList()
        assertEquals(ConfirmationOutcome.Rejected(reason), profiles.record(request))
        assertEquals(before, repository.events, "a rejection is never persisted")
    }

    @AfterTest fun everyStateKeepsCalculationAndTreatmentBlocked() {
        seen.forEach {
            assertFalse(it.allowsCalculation || it.allowsTreatment, it.toString())
            assertEquals(ClinicalEligibility.NOT_APPROVED, it.eligibility)
            assertEquals("profile.not_approved_for_calculation", it.blockCode)
        }
    }

    @Test fun missingPendingAndUnreadableAreDistinct() {
        val missing = state()
        assertTrue(missing is ProfileGateState.Missing)
        assertEquals(listOf("profile.history.missing"), missing.detailCodes)
        assertEquals(listOf("profile.read.pending"), ProfileGateState.ReadPending.detailCodes.also { seen.add(ProfileGateState.ReadPending) })
        val failed = ProfileGateState.of(ProfileRecordRead.Failed(ProfileFailure.READ_FAILED), zones).also { seen.add(it) }
        assertEquals(ProfileGateState.Unreadable(ProfileFailure.READ_FAILED), failed)
        assertEquals(listOf("profile.storage.read_failed"), failed.detailCodes)
        // Nothing to confirm or revoke without a saved version.
        rejected(ConfirmationRequest.Confirm(ids.next(), 1, "a".repeat(64), 0), ProfileFailure.CONFIRMATION_STALE_VERSION)
        rejected(ConfirmationRequest.Revoke(ids.next(), 1, 0), ProfileFailure.CONFIRMATION_NOT_ACTIVE)
    }

    @Test fun incompleteLatestVersionCannotBeConfirmed() {
        profiles.saved(profiles.newProfile().with(content(sensitivity = ProfileValue.NotConfigured)))
        val e6 = evaluated()
        assertEquals(VersionConfirmation.Unconfirmed, e6.confirmation)
        assertEquals(listOf(CompletenessGap.ValueNotConfigured(ProfileParameter.INSULIN_SENSITIVITY, 0, 1440)), e6.gaps)
        assertEquals(listOf("profile.gate.incomplete"), e6.detailCodes)
        assertFalse(e6.canConfirm)
        assertFalse(e6.canRevoke)
        assertNull(profiles.revokeRequest(e6, ids.next()))
        rejected(profiles.confirmRequest(e6, ids.next()), ProfileFailure.CONFIRMATION_INCOMPLETE)
    }

    @Test fun confirmRevokeAndReconfirmAreSeparateAppendOnlyFacts() {
        val v1 = profiles.saved(profiles.newProfile().with(content(ratio = entered("0"))))
        val e7 = evaluated()
        assertEquals(listOf("profile.confirmation.missing"), e7.detailCodes)
        assertTrue(e7.canConfirm)

        val confirm = profiles.confirmRequest(e7, ids.next())
        val first = recorded(confirm)
        assertFalse(first.replayed)
        assertTrue(first.confirmationActiveNow)
        assertEquals(ConfirmationEvent(1, operationId(1), ConfirmationEventKind.CONFIRM, 1, 1, v1.contentSha256, null, 0,
            5_001, "test/synthetic"), first.event)
        val e9 = first.state as ProfileGateState.Evaluated
        assertEquals(VersionConfirmation.Active(first.event), e9.confirmation)
        assertEquals(listOf("profile.not_approved_for_calculation"), e9.detailCodes)
        assertEquals(e9, state())
        assertFalse(first.allowsCalculation || first.allowsTreatment)
        // Confirming wrote no version and changed no content or fingerprint.
        assertEquals(listOf(v1), repository.versions)

        // Another confirmation of the same version while one is active.
        rejected(confirmNow(), ProfileFailure.CONFIRMATION_ALREADY_ACTIVE)

        val revocation = recorded(revokeNow())
        assertEquals(ConfirmationEventKind.REVOKE, revocation.event.kind)
        assertEquals(1L, revocation.event.revokesSeq)
        val e10 = revocation.state as ProfileGateState.Evaluated
        assertEquals(VersionConfirmation.Revoked(first.event, revocation.event), e10.confirmation)
        assertEquals(listOf("profile.confirmation.revoked"), e10.detailCodes)
        assertTrue(e10.canConfirm)

        // Retrying the old confirmation returns history and the current revoked state; it never reactivates.
        val retried = recorded(confirm)
        assertTrue(retried.replayed)
        assertEquals(first.event, retried.event)
        assertFalse(retried.confirmationActiveNow)
        assertEquals(e10.confirmation, (retried.state as ProfileGateState.Evaluated).confirmation)
        assertEquals(2, repository.events.size)
        // Revoking what is no longer active.
        rejected(ConfirmationRequest.Revoke(ids.next(), 1, 2), ProfileFailure.CONFIRMATION_NOT_ACTIVE)

        // Reconfirming is a new explicit action with a new operation identity.
        val again = recorded(confirmNow())
        assertFalse(again.replayed)
        assertEquals(3L, again.event.seq)
        assertEquals(VersionConfirmation.Active(again.event), (again.state as ProfileGateState.Evaluated).confirmation)
        assertEquals(listOf(1L, 2L, 3L), repository.events.map { it.seq })
    }

    @Test fun newVersionsNeverInheritConfirmationEvenWithTheSameFingerprint() {
        val v1 = profiles.saved(profiles.newProfile().with(content()))
        val c1 = recorded(confirmNow()).event
        val eventsBeforeSave = repository.events.toList()
        saveNew(content(ratio = entered("11")))
        assertEquals(eventsBeforeSave, repository.events, "saving creates or revokes no event")
        val e8 = evaluated()
        assertEquals(VersionConfirmation.Unconfirmed, e8.confirmation)
        assertEquals(listOf(c1), e8.superseded)
        assertEquals(listOf("profile.confirmation.missing", "profile.confirmation.superseded"), e8.detailCodes)

        // Restoring the confirmed version creates version 3 with v1's exact fingerprint: still unconfirmed.
        val restore = profiles.restore(profiles.loaded(), 1) as ProfileRestore.Ready
        val v3 = profiles.saved(restore.editor)
        assertEquals(ProfileOrigin.RESTORED, v3.origin)
        assertEquals(v1.contentSha256, v3.contentSha256)
        val restored = evaluated()
        assertEquals(VersionConfirmation.Unconfirmed, restored.confirmation)
        assertTrue(restored.canConfirm)
        // The old confirmation cannot be revoked any more: it is not the latest version's.
        rejected(ConfirmationRequest.Revoke(ids.next(), c1.seq, 1), ProfileFailure.CONFIRMATION_NOT_ACTIVE)
        val c3 = recorded(confirmNow()).event
        assertEquals(3L, c3.profileVersion)
        assertEquals(VersionConfirmation.Active(c1), evaluated().record.confirmationOf(1))
        assertEquals(VersionConfirmation.Active(c3), evaluated().confirmation)
    }

    @Test fun unitTransitionsFollowTheApprovedRules() {
        // First declaration of a unit with complete values: confirmable.
        profiles.saved(profiles.newProfile().with(content(unit = Setting.NotConfigured,
            sensitivity = ProfileValue.NotConfigured, target = ProfileValue.NotConfigured)))
        assertFalse(evaluated().canConfirm)
        saveNew(content())
        assertTrue(evaluated().canConfirm)
        val c2 = recorded(confirmNow()).event

        // Changing from a declared unit empties ISF and target: incomplete by construction, not confirmable.
        saveNew(content().withGlucoseUnit(mmol))
        val changed = evaluated()
        assertEquals(listOf(CompletenessGap.ValueNotConfigured(ProfileParameter.INSULIN_SENSITIVITY, 0, 1440),
            CompletenessGap.ValueNotConfigured(ProfileParameter.GLUCOSE_TARGET, 0, 1440)), changed.gaps)
        assertEquals(listOf("profile.gate.incomplete", "profile.confirmation.superseded"), changed.detailCodes)
        rejected(profiles.confirmRequest(changed, ids.next()), ProfileFailure.CONFIRMATION_INCOMPLETE)

        // The next version carries values in the new unit and needs its own confirmation.
        saveNew(content(unit = mmol, sensitivity = entered("2.2"), target = entered("6.1")))
        val c4 = recorded(confirmNow()).event
        assertEquals(4L, c4.profileVersion)

        // Exact restoration of a version with the other unit keeps unit and values together and may be confirmed anew.
        profiles.saved((profiles.restore(profiles.loaded(), 2) as ProfileRestore.Ready).editor)
        val restored = evaluated()
        assertEquals(mgdl, restored.latest.content.glucoseUnit)
        assertEquals(VersionConfirmation.Unconfirmed, restored.confirmation)
        assertTrue(c2 in restored.superseded && c4 in restored.superseded)
        assertEquals(5L, recorded(confirmNow()).event.profileVersion)
    }

    @Test fun retiredTimeZoneKeepsTheConfirmationAndStillAllowsRevoking() {
        profiles.saved(profiles.newProfile().with(content()))
        val confirmed = recorded(confirmNow()).event
        zones.known.remove("Europe/Madrid")
        val e11 = evaluated()
        assertEquals(VersionConfirmation.Active(confirmed), e11.confirmation)
        assertEquals(listOf(CompletenessGap.TimeZoneUnrecognized("Europe/Madrid")), e11.gaps)
        assertEquals(listOf("profile.gate.time_zone_unrecognized"), e11.detailCodes)
        assertFalse(e11.canConfirm)
        assertTrue(e11.canRevoke)

        val revoked = recorded(profiles.revokeRequest(e11, ids.next())!!)
        val e10 = revoked.state as ProfileGateState.Evaluated
        assertTrue(e10.confirmation is VersionConfirmation.Revoked)
        assertEquals(listOf("profile.gate.time_zone_unrecognized", "profile.confirmation.revoked"), e10.detailCodes)
        assertFalse(e10.canConfirm)
        rejected(profiles.confirmRequest(e10, ids.next()), ProfileFailure.CONFIRMATION_INCOMPLETE)

        // A new version with a recognized zone is a new, unconfirmed version.
        saveNew(content(zone = Setting.Declared(ProfileTimeZone("UTC"))))
        assertTrue(evaluated().canConfirm)
    }

    @Test fun concurrentScreensNeverActOnAStateTheyDidNotSee() {
        profiles.saved(profiles.newProfile().with(content()))
        val screenA = evaluated()
        val screenB = other.readState() as ProfileGateState.Evaluated
        recorded(profiles.confirmRequest(screenA, ids.next()))
        // Same version, other operation, stale observed state: state_changed, never two active confirmations.
        assertEquals(ConfirmationOutcome.Rejected(ProfileFailure.CONFIRMATION_STATE_CHANGED),
            other.record(other.confirmRequest(screenB, ids.next())))
        // Confirmation against a concurrent revocation: the later one fails.
        val fresh = evaluated()
        val revoke = profiles.revokeRequest(fresh, ids.next())!!
        val reconfirmFromStale = ConfirmationRequest.Confirm(ids.next(), 1, fresh.latest.contentSha256, fresh.record.lastEventSeq)
        recorded(revoke)
        rejected(reconfirmFromStale, ProfileFailure.CONFIRMATION_STATE_CHANGED)
        // A version saved between reading and confirming.
        val reviewing = evaluated()
        saveNew(content(ratio = entered("12")))
        rejected(profiles.confirmRequest(reviewing, ids.next()), ProfileFailure.CONFIRMATION_STALE_VERSION)
        rejected(ConfirmationRequest.Confirm(ids.next(), 2, "f".repeat(64), 2), ProfileFailure.CONFIRMATION_STALE_VERSION)
        assertEquals(2, repository.events.size)
    }

    @Test fun lostResponseFailedCommitAndRestartAreIdempotent() {
        profiles.saved(profiles.newProfile().with(content()))
        val request = confirmNow()
        repository.failNextCommit = true
        rejected(request, ProfileFailure.SAVE_FAILED)
        // The same intention retried after a failed commit records once.
        repository.loseNextResponse = true
        assertEquals(ConfirmationOutcome.Rejected(ProfileFailure.SAVE_FAILED), profiles.record(request))
        assertEquals(1, repository.events.size)
        // The answer was lost after commit: the retry returns the committed event, adding nothing.
        val retry = recorded(request)
        assertTrue(retry.replayed)
        assertTrue(retry.confirmationActiveNow)
        assertEquals(1, repository.events.size)
        // A restarted process (new use-case instance, no saved state) reads the real state.
        val restarted = ClinicalProfiles(repository, FixedClock(), "test/synthetic", zones).readState()
        assertEquals(state(), restarted)
        // Same key with another payload is a mismatch and writes nothing.
        rejected(ConfirmationRequest.Revoke(request.operationId, 1, 1), ProfileFailure.CONFIRMATION_OPERATION_MISMATCH)
        rejected(request.copy(observedEventSeq = 1), ProfileFailure.CONFIRMATION_OPERATION_MISMATCH)
    }

    @Test fun retriesAndSavesNeverSucceedOverAnUnprovableHistory() {
        val v1 = profiles.saved(profiles.newProfile().with(content()))
        val request = confirmNow()
        recorded(request)
        // Tampered event: the fingerprint no longer matches version 1.
        repository.events[0] = repository.events[0].copy(contentSha256 = "0".repeat(64))
        rejected(request, ProfileFailure.INVALID_RECORD)
        assertEquals(ProfileGateState.Unreadable(ProfileFailure.INVALID_RECORD), state())
        assertEquals(ProfileRead.Failed(ProfileFailure.INVALID_RECORD), repository.readVersions())
        assertEquals(ProfileHistory.Failed(ProfileFailure.INVALID_RECORD), profiles.read())
        val versionsBefore = repository.versions.toList()
        assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD),
            profiles.save(ProfileEditor(1, v1.content, content(ratio = entered("11")), null)))
        assertEquals(versionsBefore, repository.versions)
        // Unknown contract fails closed as unsupported, also for a retry.
        repository.events[0] = repository.events[0].copy(contentSha256 = v1.contentSha256, contractVersion = 2)
        rejected(request, ProfileFailure.UNSUPPORTED_SCHEMA)
        assertEquals(ProfileGateState.Unreadable(ProfileFailure.UNSUPPORTED_SCHEMA), state())
    }

    @Test fun invalidWriterIsRejectedWithoutWriting() {
        profiles.saved(profiles.newProfile().with(content()))
        val record = (repository.readRecord() as ProfileRecordRead.Loaded).record
        assertEquals(ConfirmationDecision.Reject(ProfileFailure.INVALID_RECORD),
            ConfirmationPolicy.evaluate(confirmNow(), 0, "", record, zones))
    }

    @Test fun pendingOperationsAreResolvedByIdentityFirst() {
        profiles.saved(profiles.newProfile().with(content()))
        // Committed but the answer was lost; then another screen revoked it. Recreation shows it as registered.
        val pending = confirmNow()
        repository.loseNextResponse = true
        profiles.record(pending)
        other.record(other.revokeRequest(other.readState() as ProfileGateState.Evaluated, ids.next())!!)
        val resolved = profiles.resolvePending(pending) as PendingResolution.Recorded
        assertEquals(repository.events[0], resolved.event)
        assertTrue((resolved.state as ProfileGateState.Evaluated).confirmation is VersionConfirmation.Revoked)

        // Not recorded and the confirmation state changed meanwhile: closed with state_changed.
        val stale = ConfirmationRequest.Confirm(ids.next(), 1, evaluated().latest.contentSha256, 1)
        assertEquals(ProfileFailure.CONFIRMATION_STATE_CHANGED, (profiles.resolvePending(stale) as PendingResolution.Closed).reason)
        // Nothing changed: stays open and the same identity can be retried.
        val open = profiles.confirmRequest(evaluated(), ids.next())
        assertTrue(profiles.resolvePending(open) is PendingResolution.Open)
        // Same identity with another payload.
        val mismatch = ConfirmationRequest.Revoke(pending.operationId, 1, 0)
        assertEquals(ProfileFailure.CONFIRMATION_OPERATION_MISMATCH, (profiles.resolvePending(mismatch) as PendingResolution.Closed).reason)
        // A newer version saved during the review.
        saveNew(content(ratio = entered("13")))
        assertEquals(ProfileFailure.CONFIRMATION_STALE_VERSION, (profiles.resolvePending(open) as PendingResolution.Closed).reason)
        // A read that cannot be proven keeps the operation undetermined: neither success nor failure.
        repository.events[0] = repository.events[0].copy(observedEventSeq = 0, contentSha256 = "1".repeat(64))
        val unknown = profiles.resolvePending(pending)
        assertEquals(PendingResolution.Undetermined(ProfileGateState.Unreadable(ProfileFailure.INVALID_RECORD)), unknown)
        assertEquals(PendingResolution.Undetermined(ProfileGateState.ReadPending),
            PendingConfirmations.resolve(pending, ProfileGateState.ReadPending))
    }

    @Test fun revokePendingIsResolvedLikeAConfirmation() {
        profiles.saved(profiles.newProfile().with(content()))
        recorded(confirmNow())
        val revoke = revokeNow()
        assertTrue(profiles.resolvePending(revoke) is PendingResolution.Open)
        recorded(revoke)
        assertTrue(profiles.resolvePending(revoke) is PendingResolution.Recorded)
        val notConfirm = ConfirmationRequest.Revoke(ids.next(), 2, 2)
        assertEquals(ProfileFailure.CONFIRMATION_NOT_ACTIVE, (profiles.resolvePending(notConfirm) as PendingResolution.Closed).reason)
    }
}
