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

    @Test fun aShownReadingAlwaysCarriesItsLimitations() {
        val view = DexcomReadingBlock.view(LatestReadingPresentation.Reading(123, 3_600_000L, 185_000L, set, "ab12cd34",
            "SYNTHETIC_TREND"), utc)
        assertEquals(UiText(R.string.dexcom_reading_value, listOf(123)), view.value)
        assertEquals(listOf(
            UiText(R.string.dexcom_reading_received, listOf(3L, "01:00")),
            UiText(R.string.dexcom_reading_measurement),
            UiText(R.string.dexcom_reading_freshness),
            UiText(R.string.dexcom_reading_provenance_set),
            UiText(R.string.dexcom_reading_status),
            UiText(R.string.dexcom_reading_details, listOf("ab12cd34", "SYNTHETIC_TREND")),
        ), view.lines)
    }

    @Test fun withheldAndMissingReadingsNeverShowANumber() {
        WithheldCause.entries.forEach {
            assertNull(DexcomReadingBlock.view(LatestReadingPresentation.Withheld(it, 0L, set), utc).value)
        }
        NoReadingCause.entries.forEach {
            val view = DexcomReadingBlock.view(LatestReadingPresentation.NoReading(it, "detail"), utc)
            assertNull(view.value)
            assertTrue(UiText(R.string.dexcom_reading_code, listOf(it.code)) in view.lines)
            assertTrue(UiText(R.string.dexcom_reading_code, listOf("detail")) in view.lines)
        }
        assertEquals(listOf(UiText(R.string.dexcom_reading_reading)), DexcomReadingBlock.view(null, utc).lines)
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
