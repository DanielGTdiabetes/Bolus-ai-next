package org.bolusai.next

import org.bolusai.engine.InputKind
import org.bolusai.engine.UnavailabilityReason
import org.bolusai.engine.UnavailableInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class GlucoseStatusPresenterTest {
    @Test
    fun `missing reading remains blocked and identifiable`() {
        val model = present(UnavailabilityReason.MISSING)

        assertEquals(GlucoseStatusMessageKey.MISSING, model.messageKey)
        assertEquals("input.glucose.missing", model.stableCode)
        assertFalse(model.allowsCalculation)
    }

    @Test
    fun `permission and source failures stay distinct`() {
        val permissionDenied = present(UnavailabilityReason.PERMISSION_DENIED)
        val sourceUnavailable = present(UnavailabilityReason.SOURCE_UNAVAILABLE)

        assertEquals(GlucoseStatusMessageKey.PERMISSION_DENIED, permissionDenied.messageKey)
        assertEquals(GlucoseStatusMessageKey.SOURCE_UNAVAILABLE, sourceUnavailable.messageKey)
        assertEquals("input.glucose.permission_denied", permissionDenied.stableCode)
        assertEquals("input.glucose.source_unavailable", sourceUnavailable.stableCode)
    }

    @Test
    fun `every unavailable reason blocks calculation`() {
        UnavailabilityReason.entries.forEach { reason ->
            assertFalse(present(reason).allowsCalculation)
        }
    }

    @Test
    fun `non glucose input is rejected instead of relabeled`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            GlucoseStatusPresenter.present(
                UnavailableInput(InputKind.IOB, UnavailabilityReason.MISSING),
            )
        }

        assertEquals("glucose_status.wrong_input", exception.message)
    }

    private fun present(reason: UnavailabilityReason): GlucoseStatusUiModel =
        GlucoseStatusPresenter.present(UnavailableInput(InputKind.GLUCOSE, reason))
}
