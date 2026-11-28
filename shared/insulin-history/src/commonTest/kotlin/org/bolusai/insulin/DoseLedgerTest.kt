package org.bolusai.insulin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0028: the local dose ledger. Synthetic doses only. */
class DoseLedgerTest {
    private var now = 1_000L
    private val ledger = InMemoryDoseLedger()
    private val log = DoseHistoryLog(ledger, { now }, "test/synthetic")
    private val sha = "a".repeat(64)

    private fun state() = (log.read() as LedgerRead.Available).state

    @Test fun anUnstartedLedgerKnowsNothingAndRejectsDoses() {
        assertEquals(LedgerState(null, emptyList(), emptyList()), state())
        assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.TRACKING_NOT_STARTED),
            log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null))
        assertNull(state().trackingSinceEpochMs)
    }

    @Test fun trackingStartsOnceAtTheInjectedInstant() {
        assertEquals(AppendOutcome.Appended, log.startTracking("t1"))
        now = 2_000
        assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.TRACKING_ALREADY_STARTED), log.startTracking("t2"))
        assertEquals(1_000L, state().trackingSinceEpochMs)
    }

    @Test fun aDoseIsStoredWithItsOriginAndInstant() {
        log.startTracking("t1")
        now = 5_000
        assertEquals(AppendOutcome.Appended, log.noteDose("d1", "2.5", DoseOrigin.TEST_CALCULATION, sha))
        assertEquals(listOf(DoseEvent.Dose("d1", 5_000, "test/synthetic", "2.5", DoseOrigin.TEST_CALCULATION, sha)),
            state().doses)
    }

    @Test fun aRetryOfTheSameIntentIsAReplayAndNeverASecondDose() {
        log.startTracking("t1")
        log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
        now = 9_000
        assertEquals(AppendOutcome.Replayed, log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null))
        assertEquals(AppendOutcome.Replayed, log.startTracking("t1"))
        assertEquals(1, state().doses.size)
        assertEquals(1_000L, state().doses.single().atEpochMs)
    }

    @Test fun theSameIdWithOtherContentIsAConflict() {
        log.startTracking("t1")
        log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
        assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.EVENT_ID_CONFLICT),
            log.noteDose("d1", "3", DoseOrigin.USER_NOTE, null))
        assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.EVENT_ID_CONFLICT), log.voidDose("d1", "d1"))
        assertEquals("2", state().doses.single().unitsText)
    }

    @Test fun invalidDosesAreRejectedWithStableCodes() {
        log.startTracking("t1")
        val cases = listOf(
            Triple("0", DoseOrigin.USER_NOTE, null) to DoseLedgerCodes.UNITS_NOT_POSITIVE,
            Triple("", DoseOrigin.USER_NOTE, null) to DoseLedgerCodes.UNITS_NOT_CANONICAL,
            Triple("1.50", DoseOrigin.USER_NOTE, null) to DoseLedgerCodes.UNITS_NOT_CANONICAL,
            Triple("-1", DoseOrigin.USER_NOTE, null) to DoseLedgerCodes.UNITS_NOT_CANONICAL,
            Triple("1.2345", DoseOrigin.USER_NOTE, null) to DoseLedgerCodes.UNITS_NOT_CANONICAL,
            Triple("1", DoseOrigin.USER_NOTE, sha) to DoseLedgerCodes.FINGERPRINT_ORIGIN_MISMATCH,
            Triple("1", DoseOrigin.TEST_CALCULATION, null) to DoseLedgerCodes.FINGERPRINT_ORIGIN_MISMATCH,
            Triple("1", DoseOrigin.TEST_CALCULATION, "xyz") to DoseLedgerCodes.INVALID_FINGERPRINT,
        )
        cases.forEachIndexed { index, (input, code) ->
            assertEquals(AppendOutcome.Rejected(code), log.noteDose("d$index", input.first, input.second, input.third))
        }
        assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.INVALID_EVENT_ID),
            log.noteDose("bad id", "1", DoseOrigin.USER_NOTE, null))
        assertTrue(state().doses.isEmpty())
    }

    @Test fun aClockBehindTheTrackingStartRejectsTheDose() {
        log.startTracking("t1")
        now = 500
        assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.CLOCK_BEFORE_TRACKING),
            log.noteDose("d1", "1", DoseOrigin.USER_NOTE, null))
    }

    @Test fun voidingKeepsTheDoseForAuditAndCannotBeRepeated() {
        log.startTracking("t1")
        log.noteDose("d1", "2", DoseOrigin.USER_NOTE, null)
        log.noteDose("d2", "3", DoseOrigin.USER_NOTE, null)
        assertEquals(AppendOutcome.Appended, log.voidDose("v1", "d1"))
        assertEquals(listOf("d2"), state().doses.map { it.eventId })
        assertEquals(listOf("d1"), state().voided.map { it.eventId })
        assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.ALREADY_VOIDED), log.voidDose("v2", "d1"))
        assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.UNKNOWN_DOSE), log.voidDose("v3", "nope"))
        assertEquals(AppendOutcome.Rejected(DoseLedgerCodes.UNKNOWN_DOSE), log.voidDose("v4", "t1"))
        assertEquals(AppendOutcome.Replayed, log.voidDose("v1", "d1"))
    }

    @Test fun inconsistentEventsDoNotFold() {
        val start = DoseEvent.TrackingStarted("t1", 1, "w")
        val dose = DoseEvent.Dose("d1", 2, "w", "1", DoseOrigin.USER_NOTE, null)
        assertNull(LedgerState.fold(listOf(dose)))
        assertNull(LedgerState.fold(listOf(start, start)))
        assertNull(LedgerState.fold(listOf(start, dose, dose)))
        assertNull(LedgerState.fold(listOf(start, DoseEvent.Void("v1", 3, "w", "d1"))))
        assertNull(LedgerState.fold(listOf(start, dose.copy(unitsText = "0"))))
        assertEquals(LedgerState(1, listOf(dose), emptyList()), LedgerState.fold(listOf(start, dose)))
    }
}
