package org.bolusai.next

import org.bolusai.dexcomg7.ApprovedPermissionSet
import org.bolusai.dexcomg7.LatestReadingPresentation
import org.bolusai.dexcomg7.NoReadingCause
import org.bolusai.dexcomg7.ReceptionGate
import org.bolusai.dexcomg7.WithheldCause
import org.bolusai.next.glucose.dexcom.DexcomReceiverComponent
import org.bolusai.next.glucose.dexcom.GateCause
import org.bolusai.next.glucose.dexcom.PermissionGate
import org.bolusai.next.glucose.dexcom.ReceptionGateStatus
import org.bolusai.next.ui.CurrentGlucoseView
import org.bolusai.next.ui.DexcomReadingBlock
import org.bolusai.next.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** ADR 0023, part II: what Inicio and Bolo compose. Synthetic values only. */
class DexcomReadingBlockTest {
    private val utc = ZoneId.of("UTC")
    private val set = ApprovedPermissionSet("adr0023-i2b-2026-10-12", "c".repeat(64))

    /** Measured at 01:00:00 UTC, received three seconds later. */
    private val shown = LatestReadingPresentation.Reading(123, 3_600_000L, 185_000L, 3_603_000L, set, "ab12cd34",
        "SYNTHETIC_TREND")

    @Test fun aCurrentReadingIsTheCurrentGlucoseWithItsMeasurementTime() {
        val view = DexcomReadingBlock.view(shown, utc, 3_600_000L + 185_000L)
        val measured = UiText(R.string.dexcom_reading_measured, listOf(3L, "01:00"))
        assertEquals(UiText(R.string.dexcom_reading_value, listOf(123)), view.value)
        assertEquals(listOf(
            measured,
            UiText(R.string.dexcom_reading_current),
            UiText(R.string.dexcom_reading_withheld_received, listOf("01:00")),
            UiText(R.string.dexcom_reading_provenance_set),
            UiText(R.string.dexcom_reading_status),
            UiText(R.string.dexcom_reading_details, listOf("ab12cd34", "SYNTHETIC_TREND")),
        ), view.lines)
        assertEquals(CurrentGlucoseView(UiText(R.string.dexcom_reading_value, listOf(123)),
            UiText(R.string.glucose_current_source), measured), view.current)
        // Exactly 10 minutes after the measurement it is still current.
        assertEquals(UiText(R.string.dexcom_reading_value, listOf(123)),
            DexcomReadingBlock.view(shown, utc, 3_600_000L + 600_000L).current.value)
    }

    @Test fun anExpiredReadingIsNeverTheCurrentGlucoseEvenIfItWasCurrentWhenRead() {
        // The presentation was read 185 s after the measurement; the screen composes it 10 min and 1 ms after it.
        val view = DexcomReadingBlock.view(shown, utc, 3_600_000L + 600_001L)
        val measured = UiText(R.string.dexcom_reading_measured, listOf(10L, "01:00"))
        assertEquals(CurrentGlucoseView(null, UiText(R.string.glucose_status_expired), measured), view.current)
        assertEquals(UiText(R.string.dexcom_reading_expired), view.lines[1])
        // The technical block still shows the number, labelled as expired.
        assertEquals(UiText(R.string.dexcom_reading_value, listOf(123)), view.value)
        // A clock earlier than the measurement never makes it current either.
        assertNull(DexcomReadingBlock.view(shown, utc, 3_599_999L).current.value)
    }

    @Test fun withoutAReadingTheCurrentGlucoseNamesTheCauseAndShowsNoNumber() {
        val expected = mapOf(
            NoReadingCause.NO_READING_RECORDED to R.string.glucose_status_missing,
            NoReadingCause.NEXT_PERMISSION_MISSING to R.string.glucose_status_permission_denied,
            NoReadingCause.RECEIVER_DISABLED to R.string.glucose_status_source_unavailable,
            NoReadingCause.POLICY_PENDING to R.string.glucose_status_source_unavailable,
            NoReadingCause.DURABLE_BLOCK to R.string.glucose_status_source_unavailable,
            NoReadingCause.LOG_UNREADABLE to R.string.glucose_status_source_unavailable,
            NoReadingCause.LAST_MESSAGE_REJECTED to R.string.glucose_status_parse_failed,
            NoReadingCause.SELECTION_PENDING_B1 to R.string.glucose_status_unknown,
        )
        assertEquals(NoReadingCause.entries.toSet(), expected.keys)
        expected.forEach { (cause, status) ->
            assertEquals(CurrentGlucoseView(null, UiText(status), null),
                DexcomReadingBlock.view(LatestReadingPresentation.NoReading(cause, null), utc, 0L).current)
        }
        val withheld = mapOf(
            WithheldCause.OUTSIDE_PRODUCER_INTERVAL to R.string.glucose_status_invalid,
            WithheldCause.CONFLICT to R.string.glucose_status_conflicting,
            WithheldCause.RECEPTION_AFTER_NOW to R.string.glucose_status_clock_anomaly,
            WithheldCause.FUTURE_TIMESTAMP to R.string.glucose_status_clock_anomaly,
            WithheldCause.INVALID_TIMESTAMP to R.string.glucose_status_clock_anomaly,
        )
        assertEquals(WithheldCause.entries.toSet(), withheld.keys)
        withheld.forEach { (cause, status) ->
            assertEquals(CurrentGlucoseView(null, UiText(status), null),
                DexcomReadingBlock.view(LatestReadingPresentation.Withheld(cause, 0L, set), utc, 0L).current)
        }
        assertEquals(CurrentGlucoseView(null, UiText(R.string.dexcom_reading_reading), null),
            DexcomReadingBlock.view(null, utc, 0L).current)
    }

    @Test fun withheldAndMissingReadingsNeverShowANumber() {
        WithheldCause.entries.forEach {
            assertNull(DexcomReadingBlock.view(LatestReadingPresentation.Withheld(it, 0L, set), utc, 0L).value)
        }
        NoReadingCause.entries.forEach {
            val view = DexcomReadingBlock.view(LatestReadingPresentation.NoReading(it, "detail"), utc, 0L)
            assertNull(view.value)
            assertTrue(UiText(R.string.dexcom_reading_code, listOf(it.code)) in view.lines)
            assertTrue(UiText(R.string.dexcom_reading_code, listOf("detail")) in view.lines)
        }
        assertEquals(listOf(UiText(R.string.dexcom_reading_reading)), DexcomReadingBlock.view(null, utc, 0L).lines)
    }

    @Test fun gateStatusFollowsComponentPermissionBlockAndHolders() {
        val open = PermissionGate.Open(set)
        assertEquals(ReceptionGate.RECEIVER_DISABLED,
            ReceptionGateStatus.of(DexcomReceiverComponent.DISABLED_BY_DEFAULT, true, null, open).gate)
        assertEquals(ReceptionGate.NEXT_PERMISSION_MISSING,
            ReceptionGateStatus.of(DexcomReceiverComponent.ENABLED, false, null, open).gate)
        assertEquals(ReceptionGateStatus(ReceptionGate.DURABLE_BLOCK, GateCause.SIGNERS_CHANGED.code),
            ReceptionGateStatus.of(DexcomReceiverComponent.ENABLED, true, GateCause.SIGNERS_CHANGED, open))
        assertEquals(ReceptionGateStatus(ReceptionGate.DURABLE_BLOCK, GateCause.HOLDER_SET_CHANGED.code),
            ReceptionGateStatus.of(DexcomReceiverComponent.ENABLED, true, null, PermissionGate.Closed(GateCause.HOLDER_SET_CHANGED)))
        assertEquals(ReceptionGateStatus(ReceptionGate.POLICY_PENDING, GateCause.VISIBILITY_UNPROVEN.code),
            ReceptionGateStatus.of(DexcomReceiverComponent.ENABLED, true, null, PermissionGate.Closed(GateCause.VISIBILITY_UNPROVEN)))
        assertEquals(ReceptionGate.OPEN, ReceptionGateStatus.of(DexcomReceiverComponent.ENABLED, true, null, open).gate)
    }
}
