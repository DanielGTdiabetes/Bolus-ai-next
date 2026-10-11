package org.bolusai.next.glucose.dexcom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.bolusai.dexcomg7.DexcomObservationStore
import org.bolusai.next.glucose.dexcom.storage.AndroidObservationIds
import org.bolusai.next.glucose.dexcom.storage.AndroidTransmitterHasher
import org.bolusai.next.glucose.dexcom.storage.DexcomG7Recorder
import org.bolusai.next.glucose.dexcom.storage.DexcomG7Recording
import org.bolusai.next.glucose.dexcom.storage.EpochClock
import org.bolusai.next.glucose.dexcom.storage.SqliteDexcomObservationStore
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Production configuration of the receiver (ADR 0022, R6). The policy stays Pending until the owner approves a
 * reproducible producer release and its anchors; verify.ps1 rejects any approved policy in production code.
 */
internal object DexcomReceiverConfiguration {
    const val ACTION = "com.dexcom.cgm.EXTERNAL_BROADCAST"
    val policy: DexcomSenderTrustPolicy = DexcomSenderTrustPolicy.Pending
}

/** What one broadcast produced. Every variant keeps glucose blocked. */
internal sealed interface DexcomReceipt {
    /** Another action reached the exported component: nothing was authenticated, read or stored (R4). */
    data class IgnoredAction(val action: String?) : DexcomReceipt

    /** The sender was not proven: extras never read, store never opened, nothing stored (R3; ADR 0020, E5). */
    data class SenderRejected(val outcome: DexcomG7ReceptionOutcome.SenderRejected, val payloadReads: Int) : DexcomReceipt

    data class Recorded(val recording: DexcomG7Recording, val payloadReads: Int) : DexcomReceipt
}

/**
 * Receiver logic without Android (ADR 0022). [accept] runs synchronously inside onReceive: action, then authentication,
 * then extras and parser. Only an authenticated outcome needs [record], which opens the store for that one message.
 */
internal class DexcomG7Intake(private val action: String, policy: DexcomSenderTrustPolicy) {
    private val reception = DexcomG7Reception(policy)

    sealed interface Step {
        data class Done(val receipt: DexcomReceipt) : Step
        /** [receivedAtEpochMs] was read inside onReceive, so a queued write keeps the reception instant (R5). */
        data class Record(
            val outcome: DexcomG7ReceptionOutcome.Authenticated,
            val payloadReads: Int,
            val receivedAtEpochMs: Long,
        ) : Step
    }

    /** [clock] is read once, only for an authenticated message, before anything is queued. */
    fun accept(
        receivedAction: String?,
        source: DexcomSenderEvidenceSource,
        clock: EpochClock,
        readPayload: () -> DexcomExtrasRead,
    ): Step {
        if (receivedAction != action) return Step.Done(DexcomReceipt.IgnoredAction(receivedAction))
        var reads = 0
        return when (val outcome = reception.receive(source) { reads++; readPayload() }) {
            is DexcomG7ReceptionOutcome.SenderRejected -> Step.Done(DexcomReceipt.SenderRejected(outcome, reads))
            is DexcomG7ReceptionOutcome.Authenticated -> Step.Record(outcome, reads, clock.nowEpochMs())
        }
    }

    /**
     * Opens the store only now, after authentication (R5), and closes it even if recording fails. The message is
     * stamped with the instant captured by [accept], never with the time the queued write happens to run.
     */
    fun <S> record(step: Step.Record, openStore: () -> S): DexcomReceipt.Recorded
        where S : DexcomObservationStore, S : Closeable = openStore().use { store ->
        DexcomReceipt.Recorded(DexcomG7Recorder(store) { step.receivedAtEpochMs }.record(step.outcome), step.payloadReads)
    }
}

/**
 * Entry point for the observed Dexcom G7 broadcast (ADR 0022). Declared disabled in the manifest; only `adb shell pm
 * enable` turns it on (R1). With the Pending policy every message is rejected before its extras are read. Nothing here
 * makes glucose available, shows a reading or calls the network.
 */
open class DexcomG7Receiver : BroadcastReceiver() {
    /** Overridden only by instrumentation, with a synthetic policy for the synthetic sender (R6). */
    internal open fun trustPolicy(): DexcomSenderTrustPolicy = DexcomReceiverConfiguration.policy

    /** The app's observation log. During instrumentation the app's own file is rejected (DexcomStorageGuard). */
    internal open fun openStore(context: Context): SqliteDexcomObservationStore =
        SqliteDexcomObservationStore(context, ids = AndroidObservationIds, hasher = AndroidTransmitterHasher)

    final override fun onReceive(context: Context, intent: Intent) {
        val intake = DexcomG7Intake(DexcomReceiverConfiguration.ACTION, trustPolicy())
        val source = AndroidDexcomSenderEvidenceSource(this, context.packageManager)
        val step = intake.accept(intent.action, source, wallClock) {
            intent.readDexcomPayload()
        }
        when (step) {
            is DexcomG7Intake.Step.Done -> deliver(step.receipt)
            is DexcomG7Intake.Step.Record -> {
                val pending = goAsync()
                val application = context.applicationContext
                writer.execute {
                    try {
                        deliver(intake.record(step) { openStore(application) })
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    internal companion object {
        /** One serial writer for every message, off the main thread (R5). */
        private val writer = Executors.newSingleThreadExecutor()
        private val wallClock = EpochClock { System.currentTimeMillis() }

        /** Process-local test seam: observes receipts. Production never sets it. */
        @Volatile var receipts: ((DexcomReceipt) -> Unit)? = null

        private fun deliver(receipt: DexcomReceipt) {
            receipts?.invoke(receipt)
        }
    }
}
