package org.bolusai.next.glucose.dexcom

import android.content.Context
import org.bolusai.dexcomg7.LatestReadingPresentation
import org.bolusai.dexcomg7.NoReadingCause
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationLatest
import org.bolusai.dexcomg7.ObservationLatestRead
import org.bolusai.dexcomg7.PresentLatestDexcomReading
import org.bolusai.dexcomg7.ReceptionGate
import org.bolusai.next.glucose.dexcom.storage.AndroidTransmitterHasher
import org.bolusai.next.glucose.dexcom.storage.DexcomStorageGuard
import org.bolusai.next.glucose.dexcom.storage.SqliteDexcomObservationStore

/**
 * What the platform establishes about the reception barrier, without writing anything. Inicio and Bolo re-check the
 * holders on every read so a change shows without a new message (ADR 0023, part I, "Visibilidad y ventana"); only
 * the receiver persists a durable block.
 */
internal data class ReceptionGateStatus(val gate: ReceptionGate, val detail: String?) {
    companion object {
        fun of(component: DexcomReceiverComponent, nextHoldsPermission: Boolean, block: GateCause?,
               decision: PermissionGate): ReceptionGateStatus = when {
            component != DexcomReceiverComponent.ENABLED -> ReceptionGateStatus(ReceptionGate.RECEIVER_DISABLED, component.code)
            !nextHoldsPermission -> ReceptionGateStatus(ReceptionGate.NEXT_PERMISSION_MISSING, null)
            block != null -> ReceptionGateStatus(ReceptionGate.DURABLE_BLOCK, block.code)
            decision is PermissionGate.Closed -> when {
                decision.cause == GateCause.NEXT_PERMISSION_MISSING ->
                    ReceptionGateStatus(ReceptionGate.NEXT_PERMISSION_MISSING, null)
                decision.cause.durable -> ReceptionGateStatus(ReceptionGate.DURABLE_BLOCK, decision.cause.code)
                else -> ReceptionGateStatus(ReceptionGate.POLICY_PENDING, decision.cause.code)
            }
            else -> ReceptionGateStatus(ReceptionGate.OPEN, null)
        }
    }
}

/**
 * Production reader of the latest reading for Inicio and Bolo (ADR 0023, plan steps 5 and 6). Read-only: an absent
 * log is empty without creating the file, an existing one is checked on a read-only connection first. During
 * instrumentation the app's own log is never touched (DexcomStorageGuard): the read reports the denial instead.
 */
internal fun readDexcomPresentation(
    context: Context,
    nowEpochMs: Long,
    name: String = SqliteDexcomObservationStore.DATABASE_NAME,
): LatestReadingPresentation {
    val app = context.applicationContext
    val component = DexcomReceiverStatus.read(app).component
    val holdsPermission = nextHoldsDexcomPermission(app)
    if (component != DexcomReceiverComponent.ENABLED || !holdsPermission) {
        val status = ReceptionGateStatus.of(component, holdsPermission, null, PermissionGate.Closed(GateCause.VISIBILITY_UNPROVEN))
        return PresentLatestDexcomReading.present(status.gate, status.detail,
            ObservationLatestRead.Loaded(ObservationLatest(null, emptyList())), nowEpochMs)
    }
    try {
        DexcomStorageGuard.checkDatabase(app, name)
    } catch (_: IllegalStateException) {
        return LatestReadingPresentation.NoReading(NoReadingCause.LOG_UNREADABLE, DexcomStorageGuard.REAL_DATABASE_DENIED)
    }
    val exists = app.getDatabasePath(name).exists()
    val store = if (exists) SqliteDexcomObservationStore(app, name, ObservationIds { error("dexcom_log.read_only") },
        AndroidTransmitterHasher) else null
    return try {
        val block = try {
            store?.activeBlockOfExisting(DexcomApprovedPermissionSet.POLICY_VERSION)
        } catch (_: Exception) {
            return LatestReadingPresentation.NoReading(NoReadingCause.LOG_UNREADABLE, GateCause.BLOCK_STORAGE_FAILED.code)
        }
        val decision = DexcomPermissionSetPolicy.evaluate(AndroidHolderSnapshotSource(app)())
        val status = ReceptionGateStatus.of(component, true, block, decision)
        val latest = store?.latestOfExisting() ?: ObservationLatestRead.Loaded(ObservationLatest(null, emptyList()))
        PresentLatestDexcomReading.present(status.gate, status.detail, latest, nowEpochMs)
    } finally {
        store?.close()
    }
}
