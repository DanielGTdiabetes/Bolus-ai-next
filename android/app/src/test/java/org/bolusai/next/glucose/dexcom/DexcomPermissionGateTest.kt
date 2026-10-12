package org.bolusai.next.glucose.dexcom

import org.bolusai.dexcomg7.ApprovedPermissionSet
import org.bolusai.dexcomg7.DexcomG7ParseResult
import org.bolusai.dexcomg7.DexcomG7Payload
import org.bolusai.dexcomg7.DexcomObservationStore
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.dexcomg7.ObservationLatestRead
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservationSummaryRead
import org.bolusai.dexcomg7.ObservationWrite
import org.bolusai.dexcomg7.ObservedDexcomG7Contract
import org.bolusai.dexcomg7.ObservedDexcomG7Message
import org.bolusai.dexcomg7.PayloadValue
import org.bolusai.dexcomg7.StoredMessage
import org.bolusai.dexcomg7.StoredMessageOutcome
import org.bolusai.next.glucose.dexcom.storage.EpochClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/** ADR 0023, part I: synthetic holder readings only. Anchors come from the production constants under test. */
class DexcomPermissionGateTest {
    private val dexcom = DexcomApprovedPermissionSet.members[0]
    private val legacy = DexcomApprovedPermissionSet.members[1]
    private val watch = DexcomApprovedPermissionSet.members[2]

    private fun holder(member: ApprovedMember, uid: Int, version: Long = member.exactVersionCode ?: 13L,
                       signers: Set<String> = member.signers) =
        PermissionHolder(member.packageName, uid, version, signers, setOf(member.packageName))

    private val next = PermissionHolder("org.bolusai.next", 10500, 1, setOf("e".repeat(64)), setOf("org.bolusai.next"))

    private fun snapshot(
        holders: List<PermissionHolder> = listOf(holder(dexcom, 10451), holder(legacy, 10450), holder(watch, 10350), next),
        definition: PermissionDefinition? = PermissionDefinition("com.dexcom.g7", 1),
        visible: Boolean = true,
        ownUid: Int = 10500,
    ) = HolderSnapshot.Read(visible, definition, holders, "org.bolusai.next", ownUid)

    private fun closed(snapshot: HolderSnapshot) = (DexcomPermissionSetPolicy.evaluate(snapshot) as PermissionGate.Closed).cause

    @Test fun anchorsAreTheApprovedValuesOfAdr0023() {
        assertEquals("com.dexcom.cgm.EXTERNAL_PERMISSION", DexcomApprovedPermissionSet.PERMISSION)
        assertEquals(listOf("com.dexcom.g7", "org.bolusai.companion", "com.wtachtsugar"),
            DexcomApprovedPermissionSet.members.map { it.packageName })
        assertEquals(listOf(4537L, null, null), DexcomApprovedPermissionSet.members.map { it.exactVersionCode })
    }

    @Test fun theExactApprovedSetOpensTheGateWithoutNamingASender() {
        val open = DexcomPermissionSetPolicy.evaluate(snapshot()) as PermissionGate.Open
        assertEquals(DexcomApprovedPermissionSet.POLICY_VERSION, open.provenance.policyVersion)
        assertEquals(64, open.provenance.holderSetSha256.length)
    }

    @Test fun legacyAndWatchMayUpdateWithTheSameSignersButDexcomMayNot() {
        val updated = snapshot(listOf(holder(dexcom, 10451), holder(legacy, 10450, version = 99),
            holder(watch, 10350, version = 40), next))
        assertTrue(DexcomPermissionSetPolicy.evaluate(updated) is PermissionGate.Open)
        assertEquals(GateCause.VERSION_CHANGED, closed(snapshot(listOf(holder(dexcom, 10451, version = 4538),
            holder(legacy, 10450), holder(watch, 10350), next))))
    }

    @Test fun anyChangeOfSignersSetOrDefinitionClosesDurably() {
        val cases = listOf(
            GateCause.SIGNERS_CHANGED to snapshot(listOf(holder(dexcom, 10451), holder(legacy, 10450, signers = setOf("f".repeat(64))),
                holder(watch, 10350), next)),
            GateCause.HOLDER_SET_CHANGED to snapshot(listOf(holder(dexcom, 10451), holder(legacy, 10450), holder(watch, 10350), next,
                PermissionHolder("org.example.extra", 10600, 1, setOf("a".repeat(64)), setOf("org.example.extra")))),
            GateCause.HOLDER_SET_CHANGED to snapshot(listOf(holder(dexcom, 10451), holder(legacy, 10450), next)),
            GateCause.PERMISSION_UNDEFINED to snapshot(definition = null),
            GateCause.PERMISSION_DEFINITION_CHANGED to snapshot(definition = PermissionDefinition("org.example.other", 1)),
            GateCause.PERMISSION_DEFINITION_CHANGED to snapshot(definition = PermissionDefinition("com.dexcom.g7", 0)),
            GateCause.SHARED_UID to snapshot(listOf(holder(dexcom, 10451), holder(legacy, 10450).copy(
                packagesSharingUid = setOf("org.bolusai.companion", "org.example.shared")), holder(watch, 10350), next)),
            GateCause.SHARED_UID to snapshot(listOf(holder(dexcom, 10451), holder(legacy, 10350), holder(watch, 10350), next)),
            GateCause.NEXT_IDENTITY_MISMATCH to snapshot(ownUid = 1),
            GateCause.QUERY_FAILED to HolderSnapshot.QueryFailed,
        )
        cases.forEach { (cause, snapshot) -> assertEquals(cause, closed(snapshot)) }
        assertEquals(10, cases.size)
        assertTrue(cases.all { it.first.durable })
    }

    @Test fun partialVisibilityIsNeverComparedAndNeverBlocksDurably() {
        val hidden = snapshot(listOf(next), visible = false)
        assertEquals(GateCause.VISIBILITY_UNPROVEN, closed(hidden))
        assertEquals(false, GateCause.VISIBILITY_UNPROVEN.durable)
        assertEquals(GateCause.PLATFORM_UNSUPPORTED, closed(HolderSnapshot.PlatformUnsupported))
    }

    @Test fun nextWithoutThePermissionIsAVisibleNonDurableState() {
        val withoutNext = snapshot(listOf(holder(dexcom, 10451), holder(legacy, 10450), holder(watch, 10350)))
        assertEquals(GateCause.NEXT_PERMISSION_MISSING, closed(withoutNext))
        assertEquals(false, GateCause.NEXT_PERMISSION_MISSING.durable)
    }

    private class Blocks(var failRead: Boolean = false, var failWrite: Boolean = false) : DexcomReceptionBlocks {
        val written = mutableListOf<Pair<String, GateCause>>()
        override fun activeBlock(policyVersion: String): GateCause? {
            if (failRead) error("synthetic")
            return written.firstOrNull { it.first == policyVersion }?.second
        }
        override fun block(policyVersion: String, cause: GateCause, atEpochMs: Long) {
            if (failWrite) error("synthetic")
            written += policyVersion to cause
        }
    }

    @Test fun aDurableMismatchIsPersistedAndSurvivesTheReturnOfTheApprovedSet() {
        val blocks = Blocks()
        val gate = DexcomPermissionGate(blocks)
        val changed = snapshot(definition = null)
        assertEquals(PermissionGate.Closed(GateCause.PERMISSION_UNDEFINED), gate.check({ changed }) { 1L })
        assertEquals(listOf(DexcomApprovedPermissionSet.POLICY_VERSION to GateCause.PERMISSION_UNDEFINED), blocks.written)
        var read = false
        assertEquals(PermissionGate.Closed(GateCause.DURABLE_BLOCK_ACTIVE, GateCause.PERMISSION_UNDEFINED),
            gate.check({ read = true; snapshot() }) { 2L })
        assertEquals(false, read)
    }

    @Test fun nonDurableCausesWriteNothing() {
        val blocks = Blocks()
        DexcomPermissionGate(blocks).check({ snapshot(visible = false) }) { 1L }
        DexcomPermissionGate(blocks).check({ snapshot(listOf(holder(dexcom, 10451), holder(legacy, 10450), holder(watch, 10350))) }) { 1L }
        assertTrue(blocks.written.isEmpty())
    }

    @Test fun blockStorageFailuresKeepReceptionClosed() {
        assertEquals(PermissionGate.Closed(GateCause.BLOCK_STORAGE_FAILED),
            DexcomPermissionGate(Blocks(failRead = true)).check({ snapshot() }) { 1L })
        assertEquals(PermissionGate.Closed(GateCause.BLOCK_STORAGE_FAILED, GateCause.HOLDER_SET_CHANGED),
            DexcomPermissionGate(Blocks(failWrite = true)).check({ snapshot(listOf(next)) }) { 1L })
        assertEquals(PermissionGate.Closed(GateCause.QUERY_FAILED),
            DexcomPermissionGate(Blocks()).check({ error("synthetic") }) { 1L })
    }

    private class Store(private val failRecord: Boolean = false, private val failClose: Boolean = false) :
        DexcomObservationStore, DexcomReceptionBlocks, Closeable {
        val blocks = Blocks()
        val recorded = mutableListOf<ObservationInput>()
        var closed = false
        override fun record(input: ObservationInput): ObservationWrite {
            if (failRecord) error("synthetic")
            recorded += input
            return ObservationWrite.Recorded(StoredMessage("m", input.receivedAtEpochMs, input.provenance,
                StoredMessageOutcome.PAYLOAD_UNREADABLE, null, 0, 0, 0, 0, emptyList(), emptyList(), 0), emptyList())
        }
        override fun read(): ObservationRead = ObservationRead.Failed(ObservationFailure.READ_FAILED)
        override fun summary(): ObservationSummaryRead = error("unused")
        override fun latest(): ObservationLatestRead = error("unused")
        override fun activeBlock(policyVersion: String) = blocks.activeBlock(policyVersion)
        override fun block(policyVersion: String, cause: GateCause, atEpochMs: Long) = blocks.block(policyVersion, cause, atEpochMs)
        override fun close() { closed = true; if (failClose) error("synthetic") }
    }

    private val clock = EpochClock { 50L }
    private val payload = DexcomG7Payload(ObservedDexcomG7Contract.ACTION, mapOf("sensorType" to PayloadValue.Text("G7")))

    @Test fun aClosedGateNeverReadsThePayloadAndStoresNothing() {
        val store = Store()
        var reads = 0
        val receipt = DexcomPermissionIntake().process(10L, { store }, { snapshot(visible = false) }, clock) {
            reads++; DexcomExtrasRead.Read(payload)
        }
        assertEquals(DexcomReceipt.GateRejected(PermissionGate.Closed(GateCause.VISIBILITY_UNPROVEN), 0), receipt)
        assertEquals(0, reads)
        assertTrue(store.recorded.isEmpty())
        assertTrue(store.closed)
        assertEquals("input.glucose.policy_not_approved", (receipt as DexcomReceipt.GateRejected).glucose.code)
    }

    @Test fun anOpenGateRecordsTheMessageWithPermissionSetProvenanceAtTheReceptionInstant() {
        val store = Store()
        val message = ObservedDexcomG7Message(null, emptyList(), 0, emptyList(), emptyList(), 0)
        val receipt = DexcomPermissionIntake { DexcomG7ParseResult.Parsed(message) }
            .process(10L, { store }, { snapshot() }, clock) { DexcomExtrasRead.Read(payload) }
        val admitted = receipt as DexcomReceipt.Admitted
        assertEquals(1, admitted.payloadReads)
        val input = store.recorded.single() as ObservationInput.Observed
        assertEquals(10L, input.receivedAtEpochMs)
        assertTrue(input.provenance is ApprovedPermissionSet)
        assertNull(input.message.authenticatedPackage)
        assertEquals("input.glucose.policy_not_approved", admitted.glucose.code)
    }

    @Test fun stageFailuresBecomeReceiptsWithoutText() {
        assertEquals(DexcomReceipt.StorageFailed(DexcomStorageFailure.OPEN_FAILED, 0),
            DexcomPermissionIntake().process<Store>(1L, { error("synthetic") }, { snapshot() }, clock) { DexcomExtrasRead.Unreadable })
        assertEquals(DexcomReceipt.IntakeFailed(DexcomIntakeFailure.EXTRAS, 1),
            DexcomPermissionIntake().process(1L, { Store() }, { snapshot() }, clock) { error("synthetic") })
        assertEquals(DexcomReceipt.IntakeFailed(DexcomIntakeFailure.PARSER, 1),
            DexcomPermissionIntake { error("synthetic") }.process(1L, { Store() }, { snapshot() }, clock) { DexcomExtrasRead.Read(payload) })
        assertEquals(DexcomReceipt.StorageFailed(DexcomStorageFailure.RECORD_FAILED, 1),
            DexcomPermissionIntake().process(1L, { Store(failRecord = true) }, { snapshot() }, clock) { DexcomExtrasRead.Unreadable })
        assertEquals(DexcomReceipt.StorageFailed(DexcomStorageFailure.CLOSE_FAILED, 1),
            DexcomPermissionIntake().process(1L, { Store(failClose = true) }, { snapshot() }, clock) { DexcomExtrasRead.Unreadable })
    }

    @Test fun theHolderFingerprintIsOrderIndependentAndCoversVersionsAndSigners() {
        val holders = listOf(holder(dexcom, 10451), holder(legacy, 10450), holder(watch, 10350), next)
        assertEquals(DexcomPermissionSetPolicy.fingerprint(holders), DexcomPermissionSetPolicy.fingerprint(holders.reversed()))
        assertTrue(DexcomPermissionSetPolicy.fingerprint(holders) !=
            DexcomPermissionSetPolicy.fingerprint(holders.map { if (it.packageName == legacy.packageName) it.copy(versionCode = 14) else it }))
    }
}
