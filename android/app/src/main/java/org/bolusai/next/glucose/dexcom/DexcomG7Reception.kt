package org.bolusai.next.glucose.dexcom

import org.bolusai.dexcomg7.DexcomG7ParseResult
import org.bolusai.dexcomg7.DexcomG7PayloadParser
import org.bolusai.dexcomg7.DexcomG7PayloadRejection
import org.bolusai.dexcomg7.ObservedDexcomG7Message
import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput

/**
 * Outcome of one broadcast. Every variant blocks glucose: [Observed] keeps `policy_not_approved` because the
 * parser's readings are NOT_APPROVED_FOR_USE (ADR 0018). The mapping is total and fixed (ADR 0019, T4).
 */
internal sealed interface DexcomG7ReceptionOutcome {
    val glucose: UnavailableInput

    data class SenderRejected(val authentication: DexcomSenderAuthenticationResult.Rejected) : DexcomG7ReceptionOutcome {
        override val glucose: UnavailableInput get() = UnavailableInput(InputKind.GLUCOSE, authentication.unavailabilityReason)
    }

    data object PayloadUnreadable : DexcomG7ReceptionOutcome {
        override val glucose: UnavailableInput get() = UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.PARSE_FAILED)
    }

    data class PayloadRejected(val rejection: DexcomG7PayloadRejection) : DexcomG7ReceptionOutcome {
        override val glucose: UnavailableInput get() = UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.PARSE_FAILED)
    }

    data class Observed(val message: ObservedDexcomG7Message) : DexcomG7ReceptionOutcome {
        override val glucose: UnavailableInput get() = UnavailableInput(InputKind.GLUCOSE, UnavailabilityReason.POLICY_NOT_APPROVED)
    }
}

/**
 * Authenticate → read extras → parse, in that order and synchronously inside onReceive (ADR 0019). [readPayload]
 * runs at most once and only after the sender is authenticated; the parser receives the authenticated package.
 * Nothing is stored, shown or sent. Unexpected defects propagate unchanged.
 */
internal class DexcomG7Reception(policy: DexcomSenderTrustPolicy) {
    private val ingress = DexcomAuthenticatedIngress(policy)

    fun receive(source: DexcomSenderEvidenceSource, readPayload: () -> DexcomExtrasRead): DexcomG7ReceptionOutcome =
        when (val result = ingress.receive(source) { sender -> afterAuthentication(sender, readPayload) }) {
            is DexcomIngressResult.Rejected -> DexcomG7ReceptionOutcome.SenderRejected(result.authentication)
            is DexcomIngressResult.Handled -> result.value
        }

    private fun afterAuthentication(
        sender: DexcomSenderAuthenticationResult.Authenticated,
        readPayload: () -> DexcomExtrasRead,
    ): DexcomG7ReceptionOutcome = when (val read = readPayload()) {
        DexcomExtrasRead.Unreadable -> DexcomG7ReceptionOutcome.PayloadUnreadable
        is DexcomExtrasRead.Read -> when (val parsed = DexcomG7PayloadParser.parse(read.payload, sender.packageName)) {
            is DexcomG7ParseResult.Rejected -> DexcomG7ReceptionOutcome.PayloadRejected(parsed.rejection)
            is DexcomG7ParseResult.Parsed -> DexcomG7ReceptionOutcome.Observed(parsed.message)
        }
    }
}
