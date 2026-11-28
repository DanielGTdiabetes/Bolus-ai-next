package org.bolusai.next.mfp

import org.bolusai.mfp.MfpFailure
import org.bolusai.mfp.MfpFoodRow
import org.bolusai.next.application.MfpImportOutcome
import org.bolusai.next.ui.MfpLinkText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0032, section 2: every state of the link is specific and visible; none is shown as "sin conexión". */
class MfpLinkTextTest {
    @Test fun everyFailureHasItsOwnMessageWithItsCodeAndNoEarlierReading() {
        val messages = MfpFailure.entries.map { MfpLinkText.failure(it) }
        assertEquals(MfpFailure.entries.size, messages.toSet().size)
        MfpFailure.entries.zip(messages).forEach { (failure, message) ->
            assertTrue(message, message.contains("(${failure.code})"))
            assertTrue(message, message.endsWith(MfpLinkText.NOT_USED))
        }
        MfpFailure.entries.filter { it != MfpFailure.TRANSPORT_FAILED }.forEach {
            assertFalse(MfpLinkText.failure(it).contains("sin red"))
        }
    }

    @Test fun unknownTotalsAreShownAsUnknownWithTheirCode() {
        val noFiber = MfpTestData.header.dropLast(1)
        val text = MfpLinkText.meal(MfpTestData.meal(MfpFoodRow("Synthetic bread, 1 slice", listOf("80", "-3", "1.5", "3")),
            header = noFiber))
        assertEquals(listOf(
            "breakfast · 1 alimentos · mfp:2026-01-15:breakfast",
            "Hidratos: sin dato (mfp.value.food_unknown)",
            "Grasa: 1,5 g",
            "Proteína: 3 g",
            "Fibra: sin dato (mfp.value.column_absent)",
            "Energía: 80 kcal",
        ), text.lines())
    }

    @Test fun offStateSaysThatNothingConnects() {
        assertTrue(MfpLinkText.OFF.contains("no abre ninguna conexión"))
        assertEquals("No se ha importado (meal.storage.save_failed).",
            MfpLinkText.outcome(MfpImportOutcome.Failed("meal.storage.save_failed")))
    }
}
