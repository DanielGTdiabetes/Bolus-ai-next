package org.bolusai.meals

import kotlin.test.*

class MealRestoreTest {
    private val source = DishReference("synthetic-dish", 4)
    private val first = MealRecord("synthetic", 1, MealKind.DRAFT,
        MealContent(carbs = draftField("0"), notes = draftField("  exacto  ")), source)
    private val second = first.copy(revision = 2, content = first.content.copy(carbs = draftField("texto nuevo"),
        fat = draftField("0"), basis = NutritionBasis.TOTAL_GRAMS))
    private val third = second.copy(revision = 3, content = MealContent(name = draftField("última")))
    private val history = MealHistory.Loaded("synthetic", MealKind.DRAFT, listOf(third, second, first))

    /** Restoration and editing are pure: any storage access is a defect. */
    private object NoStorage : MealRepository {
        override fun read(kind: MealKind): MealRead = error("restore must not read")
        override fun save(editor: MealRecord): MealSave = error("restore must not save")
    }

    private val meals = MealDrafts(NoStorage, MealIds { error("restore must not create identities") })

    @Test fun restoredEditorKeepsIdentityAndExactOlderContentIncludingAbsenceAndZero() {
        val ready = assertIs<MealRestore.Ready>(meals.restore(history, 1))

        assertEquals(third, ready.baseline)
        assertEquals(MealRecord("synthetic", 3, MealKind.DRAFT, first.content, source, restoredFrom = 1), ready.editor)
        assertEquals(DraftField.Entered("0"), ready.editor.content.carbs)
        assertEquals(DraftField.Missing, ready.editor.content.fat)
        assertEquals(DraftField.Entered("  exacto  "), ready.editor.content.notes)
        assertEquals(NutritionBasis.UNSPECIFIED, ready.editor.content.basis)
        assertFalse(ready.allowsCalculation)
        assertFalse(ready.allowsTreatment)
        assertFalse(ready.editor.allowsCalculation)
        // Saving later yields revision 4 with valid provenance.
        assertTrue(ready.editor.copy(revision = 4).hasValidSavedProvenance)
    }

    @Test fun latestOrUnknownRevisionIsRejectedWithoutAnyEditor() {
        listOf(3L, 4L, 0L, -1L, Long.MAX_VALUE).forEach {
            assertEquals(MealRestore.Rejected(MealFailure.INVALID_RECORD), meals.restore(history, it), "$it")
        }
        val single = MealHistory.Loaded("synthetic", MealKind.DRAFT, listOf(first))
        assertEquals(MealRestore.Rejected(MealFailure.INVALID_RECORD), meals.restore(single, 1))
    }

    @Test fun editsKeepProvenanceOnlyDuringARestoreSession() {
        val ready = assertIs<MealRestore.Ready>(meals.restore(history, 2))
        val changed = MealContent(name = draftField("cambio"))
        // Restore session: provenance survives edits, even back to the latest content.
        assertEquals(2L, meals.change(ready.editor, changed, ready.baseline, 2).restoredFrom)
        assertEquals(2L, meals.change(ready.editor, third.content, ready.baseline, 2).restoredFrom)
        // Editor opened on a saved restored revision: inherited provenance is dropped once it changes.
        val savedRestored = third.copy(revision = 4, restoredFrom = 2)
        assertNull(meals.change(savedRestored, changed, savedRestored, null).restoredFrom)
        assertEquals(savedRestored, meals.change(savedRestored.copy(content = changed, restoredFrom = null),
            savedRestored.content, savedRestored, null))
        // New editors never carry provenance.
        val fresh = MealRecord("fresh", 0, MealKind.DISH, MealContent())
        assertNull(meals.change(fresh, changed, null, null).restoredFrom)
    }

    @Test fun provenanceInvariantsFailClosed() {
        assertFailsWith<IllegalArgumentException> { first.copy(restoredFrom = 1) }
        assertFailsWith<IllegalArgumentException> { second.copy(restoredFrom = 0) }
        assertFailsWith<IllegalArgumentException> { MealRecord("new", 0, MealKind.DRAFT, MealContent(), restoredFrom = 1) }
        assertFalse(second.copy(restoredFrom = 1).hasValidSavedProvenance)
        assertTrue(third.copy(restoredFrom = 1).hasValidSavedProvenance)
        val invalid = listOf(first, second.copy(restoredFrom = 1))
        assertEquals(MealHistory.Failed(MealFailure.INVALID_RECORD), ReadMealHistory(object : MealHistoryRepository {
            override fun readRevisions(id: String) = MealRead.Loaded(invalid)
        }).read("synthetic", MealKind.DRAFT))
        val valid = listOf(first, second, third.copy(restoredFrom = 1))
        val loaded = assertIs<MealHistory.Loaded>(ReadMealHistory(object : MealHistoryRepository {
            override fun readRevisions(id: String) = MealRead.Loaded(valid)
        }).read("synthetic", MealKind.DRAFT))
        assertEquals(1L, loaded.latest.restoredFrom)
        assertFailsWith<IllegalArgumentException> {
            MealRestore.Ready(third.copy(content = first.content), third)
        }
        assertFailsWith<IllegalArgumentException> {
            MealRestore.Ready(third.copy(id = "other", restoredFrom = 1), third)
        }
    }

    @Test fun restoredEditorSavesThroughTheUsualConflictControl() {
        val records = mutableMapOf(third.id to third)
        val repository = object : MealRepository {
            override fun read(kind: MealKind) = MealRead.Loaded(records.values.toList())
            override fun save(editor: MealRecord): MealSave {
                val old = records[editor.id]
                val next = editor.copy(revision = editor.revision + 1)
                if (old == next) return MealSave.Saved(old)
                if ((old?.revision ?: 0) != editor.revision) return MealSave.Failed(MealFailure.CONFLICT)
                return MealSave.Saved(next).also { records[editor.id] = next }
            }
        }
        val drafts = MealDrafts(repository, MealIds { "unused" })
        val editor = assertIs<MealRestore.Ready>(drafts.restore(history, 1)).editor
        val saved = assertIs<MealSave.Saved>(drafts.save(editor)).record
        assertEquals(4L, saved.revision)
        assertEquals(1L, saved.restoredFrom)
        assertEquals(saved, assertIs<MealSave.Saved>(drafts.save(editor)).record)
        // The same stale restoration against a newer revision is a conflict, not an overwrite.
        val stale = assertIs<MealRestore.Ready>(drafts.restore(history, 2)).editor
        assertEquals(MealSave.Failed(MealFailure.CONFLICT), drafts.save(stale))
        assertEquals(saved, records[third.id])
    }
}
