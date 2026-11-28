package org.bolusai.dexcomg7

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Synthetic data only (ADR 0023). No real glucose value or transmitter identifier. */
class LatestReadingPresentationTest {
    private val permissionSet = ApprovedPermissionSet("adr0023-i2b-2026-10-12", "c".repeat(64))
    private val tx = TransmitterFingerprint("ab12cd34" + "0".repeat(56))

    private fun message(
        id: String = "m",
        receivedAt: Long = 1_000_000L,
        outcome: StoredMessageOutcome = StoredMessageOutcome.OBSERVED,
        new: Int = 1, duplicates: Int = 0, conflicts: Int = 0,
        rejection: DexcomG7PayloadRejection? = null,
    ) = StoredMessage(id, receivedAt, permissionSet, outcome, rejection,
        if (outcome == StoredMessageOutcome.OBSERVED) new else 0,
        if (outcome == StoredMessageOutcome.OBSERVED) duplicates else 0,
        if (outcome == StoredMessageOutcome.OBSERVED) conflicts else 0, 0, emptyList(), emptyList(), 0)

    /** Measured at second 997, received at 1 000 000 ms: three seconds later, as observed on the Pixel. */
    private fun reading(id: String, value: Int = 123, conflictOf: String? = null, messageId: String = "m",
                        measuredAtSeconds: Long = 997L) =
        StoredReading(id, messageId, 0, tx, measuredAtSeconds, value, "SYNTHETIC_TREND", 7L, conflictOf)

    private fun present(
        latest: ObservationLatest,
        gate: ReceptionGate = ReceptionGate.OPEN,
        now: Long = 1_120_000L,
    ) = PresentLatestDexcomReading.present(gate, null, ObservationLatestRead.Loaded(latest), now)

    @Test fun showsTheReadingOfTheLastMessageAgedFromItsMeasurementTime() {
        val shown = assertIs<LatestReadingPresentation.Reading>(present(ObservationLatest(message(), listOf(reading("r")))))
        assertEquals(123, shown.valueMgDl)
        assertEquals(997_000L, shown.measuredAtEpochMs)
        assertEquals(123_000L, shown.ageMs)
        assertEquals(1_000_000L, shown.receivedAtEpochMs)
        assertEquals("ab12cd34", shown.transmitterShort)
        assertEquals("SYNTHETIC_TREND", shown.trendLiteral)
        assertEquals(ProducerUnitClaim.MG_DL_STATED_BY_PRODUCER, shown.unit)
        assertEquals(true, shown.measurementTimeVerified)
        assertEquals(ReadingFreshness.CURRENT, shown.freshness)
        assertEquals(ObservedReadingStatus.NOT_APPROVED_FOR_USE, shown.status)
        assertEquals(permissionSet, shown.provenance)
    }

    @Test fun aReadingIsCurrentUpToTenMinutesAfterItsMeasurementAndExpiredAfterwards() {
        assertEquals(600_000L, DexcomReadingValidity.MAX_AGE_MS)
        val latest = ObservationLatest(message(), listOf(reading("r")))
        fun at(now: Long) = assertIs<LatestReadingPresentation.Reading>(present(latest, now = now))
        assertEquals(ReadingFreshness.CURRENT, at(1_000_000L).freshness)
        assertEquals(ReadingFreshness.CURRENT, at(997_000L + 600_000L).freshness)
        val expired = at(997_000L + 600_001L)
        assertEquals(ReadingFreshness.EXPIRED, expired.freshness)
        assertEquals(123, expired.valueMgDl)
        // The same presentation, asked later, expires without a new read; before its measurement it is never current.
        val fresh = at(1_000_000L)
        assertEquals(ReadingFreshness.CURRENT, fresh.freshnessAt(997_000L + 600_000L))
        assertEquals(ReadingFreshness.EXPIRED, fresh.freshnessAt(997_000L + 600_001L))
        assertEquals(ReadingFreshness.EXPIRED, fresh.freshnessAt(996_999L))
    }

    @Test fun aLateDeliveryOfAnOldMeasurementIsNeverCurrent() {
        // Measured 15 minutes before it was received: the reception is recent, the measurement is not.
        val late = ObservationLatest(message(), listOf(reading("r", measuredAtSeconds = 100L)))
        val shown = assertIs<LatestReadingPresentation.Reading>(present(late, now = 1_000_001L))
        assertEquals(ReadingFreshness.EXPIRED, shown.freshness)
        assertEquals(900_001L, shown.ageMs)
    }

    @Test fun timestampsAfterTheReceptionOrBeforeTheEpochShowNoNumber() {
        fun cause(seconds: Long) = assertIs<LatestReadingPresentation.Withheld>(
            present(ObservationLatest(message(), listOf(reading("r", measuredAtSeconds = seconds))))).cause
        assertEquals(WithheldCause.FUTURE_TIMESTAMP, cause(1_001L))
        assertEquals(WithheldCause.FUTURE_TIMESTAMP, cause(Long.MAX_VALUE))
        assertEquals(WithheldCause.INVALID_TIMESTAMP, cause(-1L))
        assertEquals(WithheldCause.INVALID_TIMESTAMP, cause(Long.MIN_VALUE))
        // Whole seconds, never rounded in favour: received at 1 000 999 ms is still second 1000.
        val sameSecond = ObservationLatest(message(receivedAt = 1_000_999L), listOf(reading("r", measuredAtSeconds = 1_000L)))
        assertIs<LatestReadingPresentation.Reading>(present(sameSecond))
        val nextSecond = ObservationLatest(message(receivedAt = 1_000_999L), listOf(reading("r", measuredAtSeconds = 1_001L)))
        assertEquals(WithheldCause.FUTURE_TIMESTAMP, assertIs<LatestReadingPresentation.Withheld>(present(nextSecond)).cause)
    }

    @Test fun everyClosedGateShowsItsCauseAndNoEarlierValue() {
        val latest = ObservationLatest(message(), listOf(reading("r")))
        mapOf(
            ReceptionGate.RECEIVER_DISABLED to NoReadingCause.RECEIVER_DISABLED,
            ReceptionGate.NEXT_PERMISSION_MISSING to NoReadingCause.NEXT_PERMISSION_MISSING,
            ReceptionGate.POLICY_PENDING to NoReadingCause.POLICY_PENDING,
            ReceptionGate.DURABLE_BLOCK to NoReadingCause.DURABLE_BLOCK,
        ).forEach { (gate, cause) ->
            assertEquals(LatestReadingPresentation.NoReading(cause, "detail"),
                PresentLatestDexcomReading.present(gate, "detail", ObservationLatestRead.Loaded(latest), 0L))
        }
    }

    @Test fun emptyUnreadableAndRejectedLogsShowNoValue() {
        assertEquals(LatestReadingPresentation.NoReading(NoReadingCause.NO_READING_RECORDED, null),
            present(ObservationLatest(null, emptyList())))
        assertEquals(LatestReadingPresentation.NoReading(NoReadingCause.LOG_UNREADABLE, "dexcom_observation.corrupt_storage"),
            PresentLatestDexcomReading.present(ReceptionGate.OPEN, null,
                ObservationLatestRead.Failed(ObservationFailure.CORRUPT_STORAGE), 0L))
        val rejected = message(outcome = StoredMessageOutcome.PAYLOAD_REJECTED,
            rejection = DexcomG7PayloadRejection(DexcomG7RejectionCode.EMPTY_BATCH))
        assertEquals(LatestReadingPresentation.NoReading(NoReadingCause.LAST_MESSAGE_REJECTED, "dexcom_g7.payload.empty_batch"),
            present(ObservationLatest(rejected, emptyList())))
        val unreadable = message(outcome = StoredMessageOutcome.PAYLOAD_UNREADABLE)
        assertEquals(LatestReadingPresentation.NoReading(NoReadingCause.LAST_MESSAGE_REJECTED, "payload_unreadable"),
            present(ObservationLatest(unreadable, emptyList())))
    }

    @Test fun aBatchSelectsTheReadingMeasuredLastWhateverItsOrder() {
        val batch = message(new = 2)
        val older = reading("r1", value = 111, measuredAtSeconds = 697L)
        val newer = reading("r2", value = 222, measuredAtSeconds = 997L)
        listOf(listOf(older, newer), listOf(newer, older)).forEach {
            val shown = assertIs<LatestReadingPresentation.Reading>(present(ObservationLatest(batch, it)))
            assertEquals(222, shown.valueMgDl)
            assertEquals(997_000L, shown.measuredAtEpochMs)
        }
        // Two stored readings with the same measurement time: neither is shown.
        val tie = present(ObservationLatest(batch, listOf(reading("r1", value = 111), reading("r2", value = 222))))
        assertEquals(WithheldCause.CONFLICT, assertIs<LatestReadingPresentation.Withheld>(tie).cause)
    }

    @Test fun aMessageThatOnlyRepeatsStoredReadingsShowsNoValue() {
        val repeated = message(new = 0, duplicates = 1)
        assertEquals(LatestReadingPresentation.NoReading(NoReadingCause.SELECTION_PENDING_B1, "readings=1"),
            present(ObservationLatest(repeated, emptyList())))
    }

    @Test fun conflictsAndValuesOutsideTheProducerIntervalAreShownWithoutNumber() {
        val conflict = present(ObservationLatest(message(new = 0, conflicts = 1), listOf(reading("r2", conflictOf = "r1"))))
        assertEquals(WithheldCause.CONFLICT, assertIs<LatestReadingPresentation.Withheld>(conflict).cause)
        listOf(0, 401, -5, Int.MAX_VALUE).forEach {
            val shown = present(ObservationLatest(message(), listOf(reading("r", value = it))))
            assertEquals(WithheldCause.OUTSIDE_PRODUCER_INTERVAL, assertIs<LatestReadingPresentation.Withheld>(shown).cause)
        }
        listOf(1, 400).forEach {
            assertEquals(it, assertIs<LatestReadingPresentation.Reading>(
                present(ObservationLatest(message(), listOf(reading("r", value = it))))).valueMgDl)
        }
    }

    @Test fun aClockEarlierThanTheReceptionStatesNoElapsedTime() {
        val shown = present(ObservationLatest(message(), listOf(reading("r"))), now = 999_999L)
        assertEquals(WithheldCause.RECEPTION_AFTER_NOW, assertIs<LatestReadingPresentation.Withheld>(shown).cause)
    }

    @Test fun latestOfALoadedLogIsTheLastMessageAndItsReadings() {
        val first = message("m1")
        val last = message("m2", receivedAt = 2L)
        val read = ObservationRead.Loaded(listOf(first, last), listOf(reading("a", messageId = "m1"), reading("b", messageId = "m2")))
        assertEquals(ObservationLatest(last, listOf(reading("b", messageId = "m2"))), ObservationLatest.of(read))
        assertEquals(ObservationLatest(null, emptyList()), ObservationLatest.of(ObservationRead.Loaded(emptyList(), emptyList())))
        assertFailsWith<IllegalArgumentException> { ObservationLatest(first, listOf(reading("x", messageId = "m2"))) }
    }

    @Test fun permissionSetProvenanceIsValidatedAndNeverNamesASender() {
        assertFailsWith<IllegalArgumentException> { ApprovedPermissionSet("", "c".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { ApprovedPermissionSet("Policy", "c".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { ApprovedPermissionSet("p", "C".repeat(64)) }
        assertEquals("approved_permission_set", permissionSet.code)
        val message = ObservedDexcomG7Message(null, emptyList(), 0, emptyList(), emptyList(), 0)
        ObservationInput.Observed(1, permissionSet, message)
        assertFailsWith<IllegalArgumentException> {
            ObservationInput.Observed(1, permissionSet,
                ObservedDexcomG7Message("org.example.claim", emptyList(), 0, emptyList(), emptyList(), 0))
        }
        assertFailsWith<IllegalArgumentException> {
            ObservationInput.Observed(1, AuthenticatedSender("org.example", 1, setOf("a".repeat(64))), message)
        }
    }

    @Test fun theUnverifiedParserRequiresAPackageClaimButNeverComparesOrKeepsIt() {
        fun payload(packageName: PayloadValue?) = DexcomG7Payload(ObservedDexcomG7Contract.ACTION, buildMap {
            put("sensorType", PayloadValue.Text("G7"))
            if (packageName != null) put("packageName", packageName)
            put("glucoseValues", PayloadValue.Group(mapOf("0" to PayloadValue.Group(mapOf(
                "glucoseValue" to PayloadValue.Int32(123),
                "timestamp" to PayloadValue.Int64(50),
                "trendArrow" to PayloadValue.Text("SYNTHETIC_TREND"),
                "transmitterId" to PayloadValue.Text("SYNTH-TX"),
                "egvTxTime" to PayloadValue.Int64(7),
            )))))
        })
        val parsed = assertIs<DexcomG7ParseResult.Parsed>(
            DexcomG7PayloadParser.parseUnverifiedSender(payload(PayloadValue.Text("org.example.any"))))
        assertNull(parsed.message.authenticatedPackage)
        assertEquals(1, parsed.message.readings.size)
        assertEquals(DexcomG7RejectionCode.MISSING_FIELD, assertIs<DexcomG7ParseResult.Rejected>(
            DexcomG7PayloadParser.parseUnverifiedSender(payload(null))).rejection.code)
        assertEquals(DexcomG7RejectionCode.BLANK_TEXT, assertIs<DexcomG7ParseResult.Rejected>(
            DexcomG7PayloadParser.parseUnverifiedSender(payload(PayloadValue.Text(" ")))).rejection.code)
        assertEquals(DexcomG7RejectionCode.PACKAGE_CLAIM_MISMATCH, assertIs<DexcomG7ParseResult.Rejected>(
            DexcomG7PayloadParser.parse(payload(PayloadValue.Text("org.example.any")), "org.example.other")).rejection.code)
    }
}
