package org.bolusai.meals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MealDraftsTest {
    private class MemoryRepository : MealRepository {
        val records = mutableMapOf<String, MealRecord>()
        var fail = false
        override fun read(kind: MealKind): MealRead =
            if (fail) MealRead.Failed(MealFailure.READ_FAILED)
            else MealRead.Loaded(records.values.filter { it.kind == kind })

        override fun save(editor: MealRecord): MealSave {
            if (fail) return MealSave.Failed(MealFailure.SAVE_FAILED)
            val old = records[editor.id]
            val next = editor.copy(revision = editor.revision + 1)
            if (old == next) return MealSave.Saved(old)
            if ((old?.revision ?: 0) != editor.revision) return MealSave.Failed(MealFailure.CONFLICT)
            return MealSave.Saved(next).also {
                records[editor.id] = it.record
            }
        }
    }

    @Test fun missingAndExplicitZeroRemainDifferentAndCalculationIsAlwaysBlocked() {
        val repository = MemoryRepository()
        val meals = MealDrafts(repository, MealIds { "draft-1" })
        val editor = meals.new(MealKind.DRAFT).copy(content = MealContent(
            carbs = draftField("0"), fat = draftField(""), basis = NutritionBasis.TOTAL_GRAMS))

        val saved = assertIs<MealSave.Saved>(meals.save(editor)).record

        assertEquals(DraftField.Entered("0"), saved.content.carbs)
        assertEquals(DraftField.Missing, saved.content.fat)
        assertTrue(saved.content.hasMissingCaptureFields)
        assertFalse(saved.allowsCalculation)
        assertFalse(saved.allowsTreatment)
        assertEquals("meal.draft.not_clinically_validated", saved.clinicalBlockCode)
    }

    @Test fun copyingDishMakesIndependentDraftWithStableSourceRevision() {
        var number = 0
        val meals = MealDrafts(MemoryRepository(), MealIds { "id-${++number}" })
        val dish = MealRecord("dish", 3, MealKind.DISH,
            MealContent(name = draftField("Plato sintético")))

        val draft = meals.copyDish(dish)

        assertEquals(MealKind.DRAFT, draft.kind)
        assertEquals(DishReference("dish", 3), draft.copiedFrom)
        assertEquals(dish.content, draft.content)
        assertNotEquals(dish.id, draft.id)
        assertEquals(0, draft.revision)
    }

    @Test fun failuresAndRevisionConflictsAreNotReportedAsSaved() {
        val repository = MemoryRepository()
        val meals = MealDrafts(repository, MealIds { "draft" })
        val editor = meals.new(MealKind.DRAFT)
        repository.fail = true
        assertEquals(MealFailure.SAVE_FAILED, assertIs<MealSave.Failed>(meals.save(editor)).reason)
        repository.fail = false
        val saved = assertIs<MealSave.Saved>(meals.save(editor)).record
        assertEquals(saved, assertIs<MealSave.Saved>(meals.save(editor)).record)
        assertEquals(MealFailure.CONFLICT, assertIs<MealSave.Failed>(
            meals.save(editor.copy(content = MealContent(name = draftField("otro"))))).reason)
        assertEquals(1, saved.revision)
    }
}
