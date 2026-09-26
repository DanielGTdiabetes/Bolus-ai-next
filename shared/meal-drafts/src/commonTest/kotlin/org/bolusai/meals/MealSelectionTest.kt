package org.bolusai.meals

import kotlin.test.*

class MealSelectionTest {
    private val record = MealRecord("synthetic", 1, MealKind.DRAFT,
        MealContent(carbs = draftField("0"), notes = draftField("  texto exacto  ")),
        DishReference("source", 3))

    @Test fun pinnedAndObsoleteStatesRetainExactSnapshotAndNeverAllowClinicalUse() {
        val latest = record.copy(revision = 2, content = record.content.copy(carbs = draftField("otro")))
        val pinned = MealSelection.Reviewed(record, record)
        val obsolete = MealSelection.Reviewed(record, latest)
        assertFalse(pinned.obsolete)
        assertTrue(obsolete.obsolete)
        assertEquals("meal.selection.obsolete", obsolete.blockCode)
        assertEquals("meal.draft.not_clinically_validated", pinned.blockCode)
        assertEquals(record, obsolete.snapshot)
        assertEquals(DraftField.Entered("0"), obsolete.snapshot.content.carbs)
        assertEquals(DraftField.Missing, obsolete.snapshot.content.fat)
        assertEquals(NutritionBasis.UNSPECIFIED, obsolete.snapshot.content.basis)
        val states = listOf(pinned, obsolete, MealSelection.Missing) +
            MealFailure.entries.map { MealSelection.Failed(it) }
        states.forEach { assertFalse(it.allowsCalculation); assertFalse(it.allowsTreatment) }
    }

    @Test fun inconsistentIdentityOrRevisionCannotBecomeReviewed() {
        listOf(record.copy(id = "other"), record.copy(revision = 0),
            record.copy(content = MealContent()), record.copy(copiedFrom = null)).forEach {
            assertFailsWith<IllegalArgumentException> { MealSelection.Reviewed(record, it) }
        }
        assertFailsWith<IllegalArgumentException> {
            MealSelection.Reviewed(record.copy(revision = 0), record)
        }
    }

    @Test fun unsavedSelectionIsRejectedAndReadFailuresRemainExplicit() {
        var writes = 0
        val useCase = ReviewMealSelection(object : MealSelectionRepository {
            override fun readSelection() = MealSelection.Failed(MealFailure.READ_FAILED)
            override fun clearSelection() = MealSelection.Failed(MealFailure.SAVE_FAILED)
            override fun select(record: MealRecord): MealSelection {
                writes++
                return MealSelection.Failed(MealFailure.SAVE_FAILED)
            }
        })
        assertEquals(MealSelection.Failed(MealFailure.INVALID_RECORD), useCase.select(record.copy(revision = 0)))
        assertEquals(0, writes)
        assertEquals(MealSelection.Failed(MealFailure.READ_FAILED), useCase.read())
        assertEquals(MealSelection.Failed(MealFailure.SAVE_FAILED), useCase.select(record))
        assertEquals(MealSelection.Failed(MealFailure.SAVE_FAILED), useCase.clear())
    }

    @Test fun clearingPreservesExplicitSuccessAndEveryStorageFailureWithoutAllowingClinicalUse() {
        var result: MealSelection = MealSelection.Missing
        var clears = 0
        val useCase = ReviewMealSelection(object : MealSelectionRepository {
            override fun readSelection(): MealSelection = error("Clear must not infer success from a read")
            override fun select(record: MealRecord): MealSelection = error("Clear must not select a record")
            override fun clearSelection(): MealSelection { clears++; return result }
        })
        repeat(2) { assertEquals(MealSelection.Missing, useCase.clear()) }
        MealFailure.entries.forEach {
            result = MealSelection.Failed(it)
            val cleared = useCase.clear()
            assertEquals(result, cleared)
            assertFalse(cleared.allowsCalculation)
            assertFalse(cleared.allowsTreatment)
        }
        assertEquals(2 + MealFailure.entries.size, clears)
    }
}
