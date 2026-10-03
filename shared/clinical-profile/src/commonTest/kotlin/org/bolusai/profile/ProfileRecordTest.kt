package org.bolusai.profile

import kotlin.test.*

/** ADR 0014, sections 4.4 and 4.5: derivation and fail-closed validation of the event history. */
class ProfileRecordTest {
    private val repository = MemoryProfileRepository()
    private val profiles = profiles(repository)
    private val v1 = profiles.saved(profiles.newProfile().with(content()))
    private val v2 = profiles.saved(profiles.edit(profiles.loaded()).with(content(ratio = entered("11"))))
    private val versions = listOf(v1, v2)

    private fun confirm(seq: Long, version: ProfileVersion, op: Int = seq.toInt(), observed: Long = seq - 1,
                        sha: String = version.contentSha256, contract: Int = 1) =
        ConfirmationEvent(seq, operationId(op), ConfirmationEventKind.CONFIRM, contract, version.version, sha, null, observed,
            1_000 + seq, "test/synthetic")

    private fun revoke(seq: Long, target: Long, op: Int = seq.toInt(), observed: Long = seq - 1, contract: Int = 1) =
        ConfirmationEvent(seq, operationId(op), ConfirmationEventKind.REVOKE, contract, null, null, target, observed,
            1_000 + seq, "test/synthetic")

    private fun valid(vararg events: ConfirmationEvent): ProfileRecord =
        (ProfileRecord.validate(versions, events.toList()) as ProfileRecordRead.Loaded).record

    private fun rejected(vararg events: ConfirmationEvent, expected: ProfileFailure = ProfileFailure.INVALID_RECORD) =
        assertEquals(ProfileRecordRead.Failed(expected), ProfileRecord.validate(versions, events.toList()))

    @Test fun derivationOfTheLatestVersionNeverFallsBackToAnOlderOne() {
        assertEquals(VersionConfirmation.Unconfirmed, valid().confirmationOf(2))
        val active = valid(confirm(1, v2))
        assertEquals(VersionConfirmation.Active(confirm(1, v2)), active.confirmationOf(2))
        assertEquals(VersionConfirmation.Revoked(confirm(1, v2), revoke(2, 1)), valid(confirm(1, v2), revoke(2, 1)).confirmationOf(2))
        val reconfirmed = valid(confirm(1, v2), revoke(2, 1), confirm(3, v2))
        assertEquals(VersionConfirmation.Active(confirm(3, v2)), reconfirmed.confirmationOf(2))
        // A confirmation of version 1 is history once version 2 exists: version 2 stays unconfirmed.
        val superseded = valid(confirm(1, v1))
        assertEquals(VersionConfirmation.Unconfirmed, superseded.confirmationOf(superseded.latest!!.version))
        assertEquals(listOf(confirm(1, v1)), superseded.supersededConfirmations)
        // A revoked older confirmation is not reported as superseded.
        assertEquals(emptyList(), valid(confirm(1, v1), revoke(2, 1)).supersededConfirmations)
        assertEquals(2L, valid(confirm(1, v1), revoke(2, 1)).lastEventSeq)
        assertEquals(0L, valid().lastEventSeq)
    }

    @Test fun eventsAreReturnedInSeqOrderWhateverTheStorageOrder() {
        val record = (ProfileRecord.validate(versions, listOf(revoke(2, 1), confirm(1, v2))) as ProfileRecordRead.Loaded).record
        assertEquals(listOf(1L, 2L), record.events.map { it.seq })
        assertEquals(confirm(1, v2), record.eventByOperation(operationId(1)))
        assertNull(record.event(3))
    }

    @Test fun anyInconsistentEventFailsTheWholeRecordClosed() {
        rejected(confirm(2, v2))                                           // does not start at 1
        rejected(confirm(1, v1), confirm(3, v2, observed = 2))             // gap in seq
        rejected(confirm(1, v1), confirm(1, v2, op = 2))                   // duplicated seq
        rejected(confirm(1, v1), confirm(2, v2, op = 1))                   // repeated operation id
        rejected(confirm(1, v2, sha = "0".repeat(64)))                     // fingerprint differs from the version
        rejected(confirm(1, v2.copy(version = 3)))                         // version that does not exist
        rejected(confirm(1, v2), revoke(2, 1), revoke(3, 2))               // revocation of a revocation
        rejected(confirm(1, v2), revoke(2, 1), revoke(3, 1))               // double revocation
        rejected(confirm(1, v2), confirm(2, v2))                           // two active confirmations at once
        rejected(confirm(1, v2), confirm(2, v2, observed = 0))             // observed state is not seq - 1
        assertEquals(ProfileRecordRead.Failed(ProfileFailure.INVALID_RECORD),
            ProfileRecord.validate(emptyList(), listOf(confirm(1, v1))))   // events without any version
    }

    @Test fun unknownContractIsUnsupportedAndMalformedFieldsCannotBeBuilt() {
        rejected(confirm(1, v2, contract = 2), expected = ProfileFailure.UNSUPPORTED_SCHEMA)
        rejected(confirm(1, v2), revoke(2, 1, contract = 7), expected = ProfileFailure.UNSUPPORTED_SCHEMA)
        listOf("", "00000000-0000-4000-8000-00000000000A", "not-a-uuid", "00000000000040008000000000000001").forEach {
            assertFailsWith<IllegalArgumentException>(it) { OperationId(it) }
        }
        assertFailsWith<IllegalArgumentException> { confirm(1, v2, sha = "short") }
        assertFailsWith<IllegalArgumentException> { confirm(1, v2, observed = 1) }
        assertFailsWith<IllegalArgumentException> { revoke(1, 1) }
        assertFailsWith<IllegalArgumentException> {
            ConfirmationEvent(1, operationId(1), ConfirmationEventKind.CONFIRM, 1, 2, v2.contentSha256, 1, 0, 0, "w")
        }
        assertFailsWith<IllegalArgumentException> {
            ConfirmationEvent(2, operationId(1), ConfirmationEventKind.REVOKE, 1, 2, null, 1, 1, 0, "w")
        }
        assertEquals(ConfirmationEventKind.CONFIRM, ConfirmationEventKind.fromCode("confirm"))
        assertNull(ConfirmationEventKind.fromCode("approve"))
    }

    @Test fun invalidVersionsFailTheRecordEvenWithoutEvents() {
        assertEquals(ProfileRecordRead.Failed(ProfileFailure.INVALID_RECORD), ProfileRecord.validate(listOf(v2), emptyList()))
        assertEquals(ProfileRecordRead.Loaded(valid()), ProfileRecord.validate(versions.reversed(), emptyList()))
    }
}
