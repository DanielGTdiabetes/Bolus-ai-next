package org.bolusai.next.glucose.dexcom.storage

import org.bolusai.dexcomg7.DexcomObservationStore
import org.bolusai.dexcomg7.ObservationInput
import org.bolusai.dexcomg7.ObservationWrite
import org.bolusai.dexcomg7.TransmitterFingerprint
import org.bolusai.dexcomg7.TransmitterHasher
import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput
import org.bolusai.next.glucose.dexcom.DexcomG7ReceptionOutcome
import java.security.MessageDigest

/** Device wall clock in epoch milliseconds, injected. It only stamps reception; no freshness is derived from it. */
internal fun interface EpochClock {
    fun nowEpochMs(): Long
}

internal sealed interface DexcomG7Recording {
    val glucose: UnavailableInput

    /** Unauthenticated senders are never stored (ADR 0020, E5): nothing proves who sent the message. */
    data class NotRecorded(override val glucose: UnavailableInput) : DexcomG7Recording

    data class Recorded(override val glucose: UnavailableInput, val write: ObservationWrite.Recorded) : DexcomG7Recording

    /** A failed append is visible and blocks as persistence_failed; it is never reported as recorded. */
    data class Failed(val write: ObservationWrite.Failed) : DexcomG7Recording {
        override val glucose: UnavailableInput get() = UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.PERSISTENCE_FAILED)
    }
}

/**
 * Persists what one reception produced (ADR 0020). Every result keeps glucose blocked: a recorded observation is
 * still NOT_APPROVED_FOR_USE. Unexpected defects of the store propagate unchanged.
 */
internal class DexcomG7Recorder(private val store: DexcomObservationStore, private val clock: EpochClock) {
    fun record(outcome: DexcomG7ReceptionOutcome): DexcomG7Recording {
        val input = when (outcome) {
            is DexcomG7ReceptionOutcome.SenderRejected -> return DexcomG7Recording.NotRecorded(outcome.glucose)
            DexcomG7ReceptionOutcome.PayloadUnreadable -> ObservationInput.PayloadUnreadable(clock.nowEpochMs())
            is DexcomG7ReceptionOutcome.PayloadRejected ->
                ObservationInput.PayloadRejected(clock.nowEpochMs(), outcome.rejection)
            is DexcomG7ReceptionOutcome.Observed -> ObservationInput.Observed(clock.nowEpochMs(), outcome.message)
        }
        return when (val write = store.record(input)) {
            is ObservationWrite.Recorded -> DexcomG7Recording.Recorded(outcome.glucose, write)
            is ObservationWrite.Failed -> DexcomG7Recording.Failed(write)
        }
    }
}

/**
 * SHA-256 over a versioned domain prefix and the UTF-8 identifier (ADR 0020, E3). The prefix keeps this fingerprint
 * distinct from any other hash of the same identifier. It is not a secret: it minimizes, it does not anonymize.
 */
internal object AndroidTransmitterHasher : TransmitterHasher {
    private val PREFIX = "bolusai.dexcom.transmitter.v1\u0000".encodeToByteArray()

    override fun fingerprint(transmitterId: String): TransmitterFingerprint {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(PREFIX)
            update(transmitterId.encodeToByteArray())
        }.digest()
        return TransmitterFingerprint(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
}
