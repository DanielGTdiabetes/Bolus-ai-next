package org.bolusai.next.mfp

import org.bolusai.meals.DraftField
import org.bolusai.meals.MealContent
import org.bolusai.meals.MealFailure
import org.bolusai.meals.MealHistory
import org.bolusai.meals.MealKind
import org.bolusai.meals.MealRecord
import org.bolusai.meals.MealSave
import org.bolusai.meals.NutritionBasis
import org.bolusai.meals.draftField
import org.bolusai.mfp.MfpAmount
import org.bolusai.mfp.MfpFoodRow
import org.bolusai.mfp.MfpImportKind
import org.bolusai.next.application.ImportMfpMeal
import org.bolusai.next.application.MfpDraftImport
import org.bolusai.next.application.MfpImportOutcome
import org.bolusai.profile.DecimalInput
import org.bolusai.profile.DecimalInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0032, second delivery: a diary meal becomes a draft; unknown stays missing and a re-import writes nothing. */
class ImportMfpMealTest {
    /** In-memory drafts with the same rules as the device store: append-only, compare revision, idempotent retry. */
    private class Drafts {
        val revisions = mutableListOf<MealRecord>()
        var failRead: MealFailure? = null
        var failSave: MealFailure? = null

        fun history(id: String): MealHistory {
            failRead?.let { return MealHistory.Failed(it) }
            val own = revisions.filter { it.id == id }.sortedByDescending { it.revision }
            return if (own.isEmpty()) MealHistory.Missing else MealHistory.Loaded(id, MealKind.DRAFT, own)
        }

        fun save(editor: MealRecord): MealSave {
            failSave?.let { return MealSave.Failed(it) }
            val previous = revisions.filter { it.id == editor.id }.maxByOrNull { it.revision }
            val next = editor.copy(revision = editor.revision + 1)
            return when {
                previous == next -> MealSave.Saved(previous)
                (previous?.revision ?: 0) != editor.revision -> MealSave.Failed(MealFailure.CONFLICT)
                else -> MealSave.Saved(next.also { revisions += it })
            }
        }

        val import get() = ImportMfpMeal(::history, ::save)
    }

    private val readAt = 1_768_478_400_000 // 2026-01-15T12:00:00Z

    @Test fun newMealBecomesDraftWithItsIdentityTotalsAndProvenance() {
        val drafts = Drafts()
        val meal = MfpTestData.meal(MfpTestData.oats, MfpTestData.milk)

        val outcome = drafts.import.import(meal, readAt) as MfpImportOutcome.Imported

        assertEquals(MfpImportKind.NEW_MEAL, outcome.kind)
        val record = drafts.revisions.single()
        assertEquals(outcome.record, record)
        assertEquals("mfp:2026-01-15:breakfast", record.id)
        assertEquals(1L, record.revision)
        assertEquals(MealKind.DRAFT, record.kind)
        assertEquals(NutritionBasis.TOTAL_GRAMS, record.content.basis)
        assertEquals(draftField("36.6"), record.content.carbs)
        assertEquals(draftField("5.5"), record.content.fat)
        assertEquals(draftField("11.2"), record.content.protein)
        assertEquals(draftField("4"), record.content.fiber)
        val notes = (record.content.notes as DraftField.Entered).text.lines()
        assertTrue(notes[0].startsWith("Origen: MyFitnessPal (mfp:2026-01-15:breakfast)"))
        assertEquals("mfp-revision=${meal.revision.sha256}", notes[1])
        assertEquals("mfp-read-at=2026-01-15T12:00:00Z", notes[2])
        assertTrue("- Synthetic oats, 40 g · carbohydrates 27 · fat 2.5 · protein 5 · fiber 4 · calories 150" in notes)
        assertFalse(record.allowsCalculation || record.allowsTreatment)
    }

    @Test fun everyKnownTotalIsAcceptedByTheDraftDecimalReader() {
        val record = (Drafts().import.import(MfpTestData.meal(MfpTestData.oats, MfpTestData.milk), readAt)
            as MfpImportOutcome.Imported).record
        listOf(record.content.carbs, record.content.fat, record.content.protein, record.content.fiber).forEach {
            assertTrue(DecimalInputs.parse((it as DraftField.Entered).text) is DecimalInput.Valid)
        }
    }

    @Test fun unknownNutrientIsMissingNeverZero() {
        val noFiber = MfpTestData.header.dropLast(1)
        val unreadable = MfpFoodRow("Synthetic bread, 1 slice", listOf("80", "n/a", "1", "3"))
        val record = (Drafts().import.import(MfpTestData.meal(unreadable, header = noFiber), readAt)
            as MfpImportOutcome.Imported).record

        assertEquals(DraftField.Missing, record.content.carbs)
        assertEquals(DraftField.Missing, record.content.fiber)
        assertEquals(draftField("1"), record.content.fat)
        assertTrue(record.content.hasMissingCaptureFields)
        val notes = (record.content.notes as DraftField.Entered).text
        assertTrue("Sin dato: carbohydrates (mfp.value.food_unknown)" in notes)
        assertTrue("Sin dato: fiber (mfp.value.column_absent)" in notes)
        assertTrue("carbohydrates sin dato" in notes)
    }

    @Test fun aRealZeroIsKeptAsZero() {
        val zero = MfpFoodRow("Synthetic water, 1 glass", listOf("0", "0", "0", "0", "0"))
        val record = (Drafts().import.import(MfpTestData.meal(zero), readAt) as MfpImportOutcome.Imported).record
        assertEquals(draftField("0"), record.content.carbs)
    }

    @Test fun sameRevisionAgainWritesNothingEvenAtAnotherInstant() {
        val drafts = Drafts()
        val meal = MfpTestData.meal(MfpTestData.oats)
        drafts.import.import(meal, readAt)

        val again = drafts.import.import(meal, readAt + 60_000)

        assertEquals(MfpImportOutcome.AlreadyImported(drafts.revisions.single()), again)
        assertEquals(1, drafts.revisions.size)
    }

    @Test fun editedMealBecomesANewRevisionOfTheSameDraftAndKeepsTheOldOne() {
        val drafts = Drafts()
        val first = MfpTestData.meal(MfpTestData.oats)
        val second = MfpTestData.meal(MfpTestData.oats, MfpTestData.milk)
        drafts.import.import(first, readAt)

        val outcome = drafts.import.import(second, readAt + 1) as MfpImportOutcome.Imported

        assertEquals(MfpImportKind.NEW_REVISION, outcome.kind)
        assertEquals(listOf(1L, 2L), drafts.revisions.map { it.revision })
        assertEquals(setOf("mfp:2026-01-15:breakfast"), drafts.revisions.map { it.id }.toSet())
        assertEquals(draftField("27"), drafts.revisions[0].content.carbs)
        assertEquals(draftField("36.6"), drafts.revisions[1].content.carbs)
    }

    @Test fun theUsersOwnEditOfTheDraftIsNotOverwrittenByTheSameRevision() {
        val drafts = Drafts()
        val meal = MfpTestData.meal(MfpTestData.oats)
        val imported = (drafts.import.import(meal, readAt) as MfpImportOutcome.Imported).record
        drafts.save(imported.copy(content = imported.content.copy(carbs = draftField("30"))))

        val again = drafts.import.import(meal, readAt + 1)

        assertTrue(again is MfpImportOutcome.AlreadyImported)
        assertEquals(draftField("30"), drafts.revisions.last().content.carbs)
        assertEquals(2, drafts.revisions.size)
    }

    @Test fun aMealThatGoesBackToAnEarlierContentIsImportedAgain() {
        val drafts = Drafts()
        val a = MfpTestData.meal(MfpTestData.oats)
        val b = MfpTestData.meal(MfpTestData.oats, MfpTestData.milk)
        drafts.import.import(a, readAt)
        drafts.import.import(b, readAt + 1)

        val back = drafts.import.import(a, readAt + 2) as MfpImportOutcome.Imported

        assertEquals(3L, back.record.revision)
        assertEquals(draftField("27"), back.record.content.carbs)
    }

    @Test fun aDraftWithoutMarkerIsNeverTreatedAsAlreadyImported() {
        val drafts = Drafts()
        drafts.revisions += MealRecord("mfp:2026-01-15:breakfast", 1, MealKind.DRAFT,
            MealContent(notes = draftField("mfp-revision=not-a-hash")))

        val outcome = drafts.import.import(MfpTestData.meal(MfpTestData.oats), readAt) as MfpImportOutcome.Imported

        assertEquals(MfpImportKind.NEW_REVISION, outcome.kind)
        assertEquals(2L, outcome.record.revision)
    }

    @Test fun storageFailuresAreReportedAndNothingIsWritten() {
        val meal = MfpTestData.meal(MfpTestData.oats)
        val unreadable = Drafts().apply { failRead = MealFailure.READ_FAILED }
        assertEquals(MfpImportOutcome.Failed("meal.storage.read_failed"), unreadable.import.import(meal, readAt))
        val unwritable = Drafts().apply { failSave = MealFailure.SAVE_FAILED }
        assertEquals(MfpImportOutcome.Failed("meal.storage.save_failed"), unwritable.import.import(meal, readAt))
        assertTrue(unreadable.revisions.isEmpty() && unwritable.revisions.isEmpty())
    }

    @Test fun decimalsDropTrailingZerosOnly() {
        assertEquals("12", MfpDraftImport.decimal(MfpAmount.Known(12_000)))
        assertEquals("12.5", MfpDraftImport.decimal(MfpAmount.Known(12_500)))
        assertEquals("0.005", MfpDraftImport.decimal(MfpAmount.Known(5)))
        assertEquals("100", MfpDraftImport.decimal(MfpAmount.Known(100_000)))
    }
}
