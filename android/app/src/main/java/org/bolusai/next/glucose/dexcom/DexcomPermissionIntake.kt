package org.bolusai.next.glucose.dexcom

import org.bolusai.dexcomg7.DexcomG7ParseResult
import org.bolusai.dexcomg7.DexcomG7Payload
import org.bolusai.dexcomg7.DexcomG7PayloadParser
import org.bolusai.dexcomg7.DexcomObservationStore
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.next.glucose.dexcom.storage.EpochClock
import java.io.Closeable

/**
 * Reception by the approved permission set (ADR 0023, part I). Runs on the receiver's writer thread for one message:
 * open the log, check the gate (durable block, then a fresh holder reading), and only if it is open read the extras,
 * parse them without a sender package and record the message with [org.bolusai.dexcomg7.ApprovedPermissionSet]
 * provenance. A closed gate never reads the payload and stores nothing but, when durable, its block.
 *
 * The recorded readings stay NOT_APPROVED_FOR_USE: this path makes nothing available to [org.bolusai.next.application]
 * or the engine. Every stage converts an [Exception] into a receipt with its stage code, never its text.
 */
internal class DexcomPermissionIntake(
    private val parse: (DexcomG7Payload) -> DexcomG7ParseResult = DexcomG7PayloadParser::parseUnverifiedSender,
) {
    fun <S> process(
        receivedAtEpochMs: Long,
        openStore: () -> S,
        snapshot: () -> HolderSnapshot,
        clock: EpochClock,
        readPayload: () -> DexcomExtrasRead,
    ): DexcomReceipt where S : DexcomObservationStore, S : DexcomReceptionBlocks, S : Closeable {
        val store = try {
            openStore()
        } catch (_: Exception) {
            return DexcomReceipt.StorageFailed(DexcomStorageFailure.OPEN_FAILED, 0)
        }
        var reads = 0
        val receipt: DexcomReceipt = run {
            val gate = DexcomPermissionGate(store).check(snapshot) { clock.nowEpochMs() }
            val provenance = when (gate) {
                is PermissionGate.Closed -> return@run DexcomReceipt.GateRejected(gate, reads)
                is PermissionGate.Open -> gate.provenance
            }
            val read = try {
                reads++
                readPayload()
            } catch (_: Exception) {
                return@run DexcomReceipt.IntakeFailed(DexcomIntakeFailure.EXTRAS, reads)
            }
            val input = when (read) {
                DexcomExtrasRead.Unreadable -> ObservationInput.PayloadUnreadable(receivedAtEpochMs, provenance)
                is DexcomExtrasRead.Read -> when (val parsed = try {
                    parse(read.payload)
                } catch (_: Exception) {
                    return@run DexcomReceipt.IntakeFailed(DexcomIntakeFailure.PARSER, reads)
                }) {
                    is DexcomG7ParseResult.Rejected ->
                        ObservationInput.PayloadRejected(receivedAtEpochMs, provenance, parsed.rejection)
                    is DexcomG7ParseResult.Parsed ->
                        ObservationInput.Observed(receivedAtEpochMs, provenance, parsed.message)
                }
            }
            try {
                DexcomReceipt.Admitted(store.record(input), reads)
            } catch (_: Exception) {
                DexcomReceipt.StorageFailed(DexcomStorageFailure.RECORD_FAILED, reads)
            }
        }
        return try {
            store.close()
            receipt
        } catch (_: Exception) {
            if (receipt is DexcomReceipt.Admitted) DexcomReceipt.StorageFailed(DexcomStorageFailure.CLOSE_FAILED, reads)
            else receipt
        }
    }
}
