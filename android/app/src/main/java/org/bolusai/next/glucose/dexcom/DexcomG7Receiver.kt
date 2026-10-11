package org.bolusai.next.glucose.dexcom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.bolusai.dexcomg7.DexcomG7ParseResult
import org.bolusai.dexcomg7.DexcomG7Payload
import org.bolusai.dexcomg7.DexcomG7PayloadParser
import org.bolusai.dexcomg7.DexcomObservationStore
import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput
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

    /**
     * Opening, recording or closing the store threw (R10). Only the stage is kept: never the exception text nor the
     * payload. Nothing is retried. After [DexcomStorageFailure.CLOSE_FAILED] the append may have committed; the log
     * decides, not this receipt. Glucose stays blocked as persistence_failed.
     */
    data class StorageFailed(val failure: DexcomStorageFailure, val payloadReads: Int) : DexcomReceipt {
        val glucose: UnavailableInput get() = UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.PERSISTENCE_FAILED)
    }

    /**
     * Authentication, reading the extras, parsing or reading the clock threw inside onReceive (R11). Only the stage is kept: never the
     * exception text nor the payload. Nothing is retried or stored. After [DexcomIntakeFailure.AUTHENTICATION] the
     * extras were never read and the store was never opened.
     */
    data class IntakeFailed(val failure: DexcomIntakeFailure, val payloadReads: Int) : DexcomReceipt {
        val glucose: UnavailableInput get() = UnavailableInput(InputKind.GLUCOSE, failure.reason)
    }
}

/** Stable code of the stage that threw while storing one authenticated message (ADR 0022, R10). */
internal enum class DexcomStorageFailure(val code: String) {
    OPEN_FAILED("dexcom_receiver.store_open_failed"),
    RECORD_FAILED("dexcom_receiver.store_record_failed"),
    CLOSE_FAILED("dexcom_receiver.store_close_failed"),
}

/** Stable code of the synchronous stage that threw in [DexcomG7Intake.accept] (ADR 0022, R11). */
internal enum class DexcomIntakeFailure(val code: String, val reason: UnavailabilityReason) {
    AUTHENTICATION("dexcom_receiver.intake_authentication_failed", UnavailabilityReason.AUTHENTICATION_FAILED),
    EXTRAS("dexcom_receiver.intake_extras_failed", UnavailabilityReason.PARSE_FAILED),
    PARSER("dexcom_receiver.intake_parser_failed", UnavailabilityReason.PARSE_FAILED),
    CLOCK_FAILED("dexcom_receiver.intake_clock_failed", UnavailabilityReason.CLOCK_ANOMALY),
}

/**
 * Receiver logic without Android (ADR 0022). [accept] runs synchronously inside onReceive: action, then authentication,
 * then extras and parser. Only an authenticated outcome needs [record], which opens the store for that one message.
 */
internal class DexcomG7Intake(
    private val action: String,
    policy: DexcomSenderTrustPolicy,
    parse: (DexcomG7Payload, String) -> DexcomG7ParseResult = DexcomG7PayloadParser::parse,
) {
    private val reception = DexcomG7Reception(policy) { payload, packageName ->
        stage(DexcomIntakeFailure.PARSER) { parse(payload, packageName) }
    }

    sealed interface Step {
        data class Done(val receipt: DexcomReceipt) : Step
        /** [receivedAtEpochMs] was read inside onReceive, so a queued write keeps the reception instant (R5). */
        data class Record(
            val outcome: DexcomG7ReceptionOutcome.Authenticated,
            val payloadReads: Int,
            val receivedAtEpochMs: Long,
        ) : Step
    }

    /**
     * [clock] is read once, only when the sender is authenticated and the extras and parser finished, before anything
     * is queued. [sources] is invoked only for the observed action, inside the authentication stage.
     *
     * Runs on the main thread, where an escaping exception would kill the process (R11): any [Exception] becomes
     * [DexcomReceipt.IntakeFailed]. Each stage has its own boundary: the source factory and the authentication
     * decision, the [readPayload] call, the parser and, for an authenticated message only, the clock. The extras view keeps converting only unparcel failures into
     * an unreadable payload (ADR 0019, T3); nothing is relabelled as one here. JVM [Error]s are not caught.
     */
    fun accept(
        receivedAction: String?,
        sources: () -> DexcomSenderEvidenceSource,
        clock: EpochClock,
        readPayload: () -> DexcomExtrasRead,
    ): Step {
        if (receivedAction != action) return Step.Done(DexcomReceipt.IgnoredAction(receivedAction))
        var reads = 0
        val outcome = try {
            val source = stage(DexcomIntakeFailure.AUTHENTICATION) { sources() }
            reception.receive(source) {
                reads++
                stage(DexcomIntakeFailure.EXTRAS) { readPayload() }
            }
        } catch (failed: StageFailed) {
            return Step.Done(DexcomReceipt.IntakeFailed(failed.failure, reads))
        } catch (_: Exception) {
            // Outside the extras and parser boundaries. Before readPayload runs only the authentication decision
            // executes; after it, only the translation of the parser's result, which belongs to the parser stage.
            val failure = if (reads == 0) DexcomIntakeFailure.AUTHENTICATION else DexcomIntakeFailure.PARSER
            return Step.Done(DexcomReceipt.IntakeFailed(failure, reads))
        }
        return when (outcome) {
            is DexcomG7ReceptionOutcome.SenderRejected -> Step.Done(DexcomReceipt.SenderRejected(outcome, reads))
            is DexcomG7ReceptionOutcome.Authenticated -> {
                val receivedAt = try {
                    stage(DexcomIntakeFailure.CLOCK_FAILED) { clock.nowEpochMs() }
                } catch (failed: StageFailed) {
                    return Step.Done(DexcomReceipt.IntakeFailed(failed.failure, reads))
                }
                Step.Record(outcome, reads, receivedAt)
            }
        }
    }

    /** Marks an exception with the stage that threw it. Keeps neither its text nor its cause. */
    private class StageFailed(val failure: DexcomIntakeFailure) : Exception(failure.code, null, false, false)

    private inline fun <T> stage(failure: DexcomIntakeFailure, block: () -> T): T = try {
        block()
    } catch (inner: StageFailed) {
        throw inner
    } catch (_: Exception) {
        throw StageFailed(failure)
    }

    /**
     * Opens the store only now, after authentication (R5), and closes it whatever recording did. The message is
     * stamped with the instant captured by [accept], never with the time the queued write happens to run.
     *
     * Runs on the receiver's writer thread, where an escaping exception would kill the process (R10): any [Exception]
     * thrown while opening, recording or closing becomes [DexcomReceipt.StorageFailed] with the first stage that
     * failed. JVM [Error]s are not caught.
     */
    fun <S> record(step: Step.Record, openStore: () -> S): DexcomReceipt
        where S : DexcomObservationStore, S : Closeable {
        val store = try {
            openStore()
        } catch (_: Exception) {
            return DexcomReceipt.StorageFailed(DexcomStorageFailure.OPEN_FAILED, step.payloadReads)
        }
        val recording = try {
            DexcomG7Recorder(store) { step.receivedAtEpochMs }.record(step.outcome)
        } catch (_: Exception) {
            null
        }
        val closed = try {
            store.close()
            true
        } catch (_: Exception) {
            false
        }
        return when {
            recording == null -> DexcomReceipt.StorageFailed(DexcomStorageFailure.RECORD_FAILED, step.payloadReads)
            !closed -> DexcomReceipt.StorageFailed(DexcomStorageFailure.CLOSE_FAILED, step.payloadReads)
            else -> DexcomReceipt.Recorded(recording, step.payloadReads)
        }
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

    /** Android's evidence about the sender of the current onReceive. Creating it only keeps references (R11). */
    internal open fun senderEvidence(context: Context): DexcomSenderEvidenceSource =
        AndroidDexcomSenderEvidenceSource(this, context.packageManager)

    /** The app's observation log. During instrumentation the app's own file is rejected (DexcomStorageGuard). */
    internal open fun openStore(context: Context): SqliteDexcomObservationStore =
        SqliteDexcomObservationStore(context, ids = AndroidObservationIds, hasher = AndroidTransmitterHasher)

    final override fun onReceive(context: Context, intent: Intent) {
        val intake = DexcomG7Intake(DexcomReceiverConfiguration.ACTION, trustPolicy())
        // accept() turns exceptions of authentication, extras, parser and clock into IntakeFailed (R11).
        val step = intake.accept(intent.action, { senderEvidence(context) }, wallClock) {
            intent.readDexcomPayload()
        }
        when (step) {
            is DexcomG7Intake.Step.Done -> deliver(step.receipt)
            is DexcomG7Intake.Step.Record -> {
                val pending = goAsync()
                val application = context.applicationContext
                writer.execute {
                    // record() turns storage exceptions into StorageFailed (R10); finish() runs regardless.
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
