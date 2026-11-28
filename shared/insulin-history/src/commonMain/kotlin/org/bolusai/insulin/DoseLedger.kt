package org.bolusai.insulin

/**
 * Local ledger of rapid-acting doses the user says were administered (ADR 0028). It is the producer of the dose
 * history the candidate IOB reads. Every entry is a local note: it grants no treatment authority, is never
 * synchronised and never reaches Legacy. The ledger is append-only: a wrong dose is voided by a new event, never
 * edited or deleted.
 *
 * No clinical value lives here: no DIA, no window, no maximum dose. Units are the canonical decimal text the user
 * confirmed (at most 6 integer and 3 fractional digits, a representation bound).
 */
enum class DoseOrigin(val code: String) {
    /** The total of a test calculation that the user confirmed as administered. */
    TEST_CALCULATION("test_calculation"),

    /** A dose typed by the user, not tied to a calculation. */
    USER_NOTE("user_note"),
    ;

    companion object {
        fun fromCode(code: String): DoseOrigin? = entries.firstOrNull { it.code == code }
    }
}

/** Stable codes of the ledger. They never carry values. */
object DoseLedgerCodes {
    const val STATUS = "local_note_not_treatment_authority"
    const val INVALID_EVENT_ID = "dose_ledger.invalid_event_id"
    const val UNITS_NOT_CANONICAL = "dose_ledger.units_not_canonical"
    const val UNITS_NOT_POSITIVE = "dose_ledger.units_not_positive"
    const val INVALID_FINGERPRINT = "dose_ledger.invalid_calculation_fingerprint"
    const val INVALID_CALCULATION_RECORD = "dose_ledger.invalid_calculation_record"
    const val FINGERPRINT_ORIGIN_MISMATCH = "dose_ledger.calculation_fingerprint_origin_mismatch"
    const val INVALID_WRITER = "dose_ledger.invalid_writer"
    const val TRACKING_NOT_STARTED = "dose_ledger.tracking_not_started"
    const val TRACKING_ALREADY_STARTED = "dose_ledger.tracking_already_started"
    const val CLOCK_BEFORE_TRACKING = "dose_ledger.clock_before_tracking_start"
    const val UNKNOWN_DOSE = "dose_ledger.unknown_dose"
    const val ALREADY_VOIDED = "dose_ledger.dose_already_voided"
    const val EVENT_ID_CONFLICT = "dose_ledger.event_id_conflict"
    const val WRITE_FAILED = "dose_ledger.write_failed"
    const val READ_FAILED = "dose_ledger.read_failed"
    const val UNSUPPORTED_SCHEMA = "dose_ledger.unsupported_schema"
    const val CORRUPT_ROW = "dose_ledger.corrupt_row"
    const val UNAVAILABLE_IN_INSTRUMENTATION = "dose_ledger.unavailable_in_instrumentation"
}

/**
 * Immutable audit record of the test calculation a dose came from, stored with the dose so it survives the process
 * (AGENTS.md section 6). [requestText] is the canonical text of the engine request (normalised inputs, profile version
 * and fingerprint, engine and rule set) whose SHA-256 is [inputSha256]; [resultText] is the breakdown, total, warnings
 * and identities of the result, including the IOB used. The ledger stores them verbatim and never interprets them.
 */
data class CalculationRecord(val inputSha256: String, val requestText: String, val resultText: String) {
    companion object {
        /** Technical storage bound of each text, not a clinical limit. */
        const val MAX_TEXT_LENGTH = 65_536
    }
}

/** One appended fact. [eventId] is the idempotency key chosen once per user intent. [atEpochMs] is the injected clock. */
sealed interface DoseEvent {
    val eventId: String
    val atEpochMs: Long
    val writer: String

    /**
     * From [atEpochMs] on, the user vouches that every rapid-acting dose is noted here. Before it the ledger knows
     * nothing, so the history can never claim an earlier coverage.
     */
    data class TrackingStarted(override val eventId: String, override val atEpochMs: Long, override val writer: String) :
        DoseEvent

    /** A dose administered at [atEpochMs]. Extended boluses are not recorded yet: every dose is an ordinary bolus. */
    data class Dose(
        override val eventId: String,
        override val atEpochMs: Long,
        override val writer: String,
        val unitsText: String,
        val origin: DoseOrigin,
        /** The test calculation the dose came from; present exactly for [DoseOrigin.TEST_CALCULATION]. */
        val calculation: CalculationRecord?,
    ) : DoseEvent

    /** Voids the dose [doseEventId]; the dose row stays in the ledger. */
    data class Void(
        override val eventId: String,
        override val atEpochMs: Long,
        override val writer: String,
        val doseEventId: String,
    ) : DoseEvent
}

object DoseEvents {
    private val EVENT_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val UNITS = Regex("^(0|[1-9][0-9]{0,5})(\\.[0-9]{0,2}[1-9])?$")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** Structural validation shared by the use case and the storage codec. Returns a stable code or null. */
    fun defect(event: DoseEvent): String? {
        if (!EVENT_ID.matches(event.eventId)) return DoseLedgerCodes.INVALID_EVENT_ID
        if (event.writer.isEmpty() || event.writer.length > 128 || event.writer.any { it.code !in 0x20..0x7e }) {
            return DoseLedgerCodes.INVALID_WRITER
        }
        return when (event) {
            is DoseEvent.TrackingStarted -> null
            is DoseEvent.Void -> if (EVENT_ID.matches(event.doseEventId)) null else DoseLedgerCodes.INVALID_EVENT_ID
            is DoseEvent.Dose -> when {
                !UNITS.matches(event.unitsText) -> DoseLedgerCodes.UNITS_NOT_CANONICAL
                event.unitsText == "0" -> DoseLedgerCodes.UNITS_NOT_POSITIVE
                event.calculation != null && !SHA256.matches(event.calculation.inputSha256) ->
                    DoseLedgerCodes.INVALID_FINGERPRINT
                event.calculation != null && listOf(event.calculation.requestText, event.calculation.resultText)
                    .any { it.isEmpty() || it.length > CalculationRecord.MAX_TEXT_LENGTH } ->
                    DoseLedgerCodes.INVALID_CALCULATION_RECORD
                (event.origin == DoseOrigin.TEST_CALCULATION) != (event.calculation != null) ->
                    DoseLedgerCodes.FINGERPRINT_ORIGIN_MISMATCH
                else -> null
            }
        }
    }
}

/**
 * What the ledger holds. [trackingSinceEpochMs] is null until the user starts the ledger. [doses] are the doses not
 * voided, in the order they were appended; [voided] the voided ones, kept for audit.
 */
data class LedgerState(
    val trackingSinceEpochMs: Long?,
    val doses: List<DoseEvent.Dose>,
    val voided: List<DoseEvent.Dose>,
) {
    companion object {
        /** Folds events in append order. Returns null when they are inconsistent, which the store reports as corrupt. */
        fun fold(events: List<DoseEvent>): LedgerState? {
            var since: Long? = null
            val doses = LinkedHashMap<String, DoseEvent.Dose>()
            val voided = LinkedHashMap<String, DoseEvent.Dose>()
            val ids = HashSet<String>()
            for (event in events) {
                if (DoseEvents.defect(event) != null || !ids.add(event.eventId)) return null
                when (event) {
                    is DoseEvent.TrackingStarted -> if (since != null) return null else since = event.atEpochMs
                    is DoseEvent.Dose -> if (since == null) return null else doses[event.eventId] = event
                    is DoseEvent.Void -> voided[event.doseEventId] = doses.remove(event.doseEventId) ?: return null
                }
            }
            return LedgerState(since, doses.values.toList(), voided.values.toList())
        }
    }
}

sealed interface LedgerRead {
    data class Available(val state: LedgerState) : LedgerRead

    /** The ledger could not be read. Unknown history is never an empty history. */
    data class Failed(val code: String) : LedgerRead
}

sealed interface AppendOutcome {
    data object Appended : AppendOutcome

    /** The same event was already stored: a retry, not a second dose. */
    data object Replayed : AppendOutcome
    data class Rejected(val code: String) : AppendOutcome
}

/**
 * Storage port. [append] stores the event durably and atomically, enforces the ledger invariants against the stored
 * events and is idempotent on [DoseEvent.eventId]: an identical event is [AppendOutcome.Replayed], a different event
 * with the same id is rejected with [DoseLedgerCodes.EVENT_ID_CONFLICT].
 */
interface DoseLedger {
    fun read(): LedgerRead
    fun append(event: DoseEvent): AppendOutcome
}

fun interface DoseClock {
    fun nowEpochMs(): Long
}

/**
 * Invariants of an append against the current state, shared by every store. [stored] is the event already stored
 * under the same id, if any.
 */
object DoseLedgerRules {
    /** A retry carries the instant of its own attempt: the intent is everything but the instant. */
    fun sameIntent(stored: DoseEvent, event: DoseEvent): Boolean = when (stored) {
        is DoseEvent.TrackingStarted -> stored.copy(atEpochMs = event.atEpochMs) == event
        is DoseEvent.Dose -> stored.copy(atEpochMs = event.atEpochMs) == event
        is DoseEvent.Void -> stored.copy(atEpochMs = event.atEpochMs) == event
    }

    fun check(state: LedgerState, stored: DoseEvent?, event: DoseEvent): AppendOutcome {
        DoseEvents.defect(event)?.let { return AppendOutcome.Rejected(it) }
        if (stored != null) {
            return if (sameIntent(stored, event)) AppendOutcome.Replayed
            else AppendOutcome.Rejected(DoseLedgerCodes.EVENT_ID_CONFLICT)
        }
        val since = state.trackingSinceEpochMs
        return when (event) {
            is DoseEvent.TrackingStarted ->
                if (since != null) AppendOutcome.Rejected(DoseLedgerCodes.TRACKING_ALREADY_STARTED) else AppendOutcome.Appended
            is DoseEvent.Dose -> when {
                since == null -> AppendOutcome.Rejected(DoseLedgerCodes.TRACKING_NOT_STARTED)
                event.atEpochMs < since -> AppendOutcome.Rejected(DoseLedgerCodes.CLOCK_BEFORE_TRACKING)
                else -> AppendOutcome.Appended
            }
            is DoseEvent.Void -> when {
                state.voided.any { it.eventId == event.doseEventId } -> AppendOutcome.Rejected(DoseLedgerCodes.ALREADY_VOIDED)
                state.doses.none { it.eventId == event.doseEventId } -> AppendOutcome.Rejected(DoseLedgerCodes.UNKNOWN_DOSE)
                else -> AppendOutcome.Appended
            }
        }
    }
}

/**
 * Use cases of the ledger. The caller passes one [eventId] per user intent and keeps it across retries. The instant
 * always comes from the injected clock: a dose is noted when it is administered, never back-dated.
 */
class DoseHistoryLog(private val ledger: DoseLedger, private val clock: DoseClock, private val writer: String) {
    fun read(): LedgerRead = ledger.read()

    fun startTracking(eventId: String): AppendOutcome =
        ledger.append(DoseEvent.TrackingStarted(eventId, clock.nowEpochMs(), writer))

    fun noteDose(eventId: String, unitsText: String, origin: DoseOrigin, calculation: CalculationRecord?): AppendOutcome =
        ledger.append(DoseEvent.Dose(eventId, clock.nowEpochMs(), writer, unitsText, origin, calculation))

    fun voidDose(eventId: String, doseEventId: String): AppendOutcome =
        ledger.append(DoseEvent.Void(eventId, clock.nowEpochMs(), writer, doseEventId))
}

/** In-memory ledger for tests and previews. Not durable. */
class InMemoryDoseLedger : DoseLedger {
    private val events = mutableListOf<DoseEvent>()

    override fun read(): LedgerRead =
        LedgerState.fold(events)?.let { LedgerRead.Available(it) } ?: LedgerRead.Failed(DoseLedgerCodes.CORRUPT_ROW)

    override fun append(event: DoseEvent): AppendOutcome {
        val state = LedgerState.fold(events) ?: return AppendOutcome.Rejected(DoseLedgerCodes.CORRUPT_ROW)
        val outcome = DoseLedgerRules.check(state, events.firstOrNull { it.eventId == event.eventId }, event)
        if (outcome == AppendOutcome.Appended) events += event
        return outcome
    }
}
