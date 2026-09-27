package org.bolusai.meals

import kotlin.test.*

class MealHistoryTest {
    private val source = DishReference("synthetic-dish", 4)
    private val first = MealRecord("synthetic", 1, MealKind.DRAFT,
        MealContent(carbs = draftField("0"), notes = draftField("  exacto  ")), source)
    private val second = first.copy(revision = 2, content = first.content.copy(carbs = draftField("texto nuevo")))
    private val third = second.copy(revision = 3, content = MealContent(basis = NutritionBasis.TOTAL_GRAMS))

    private class Repository(var result: MealRead) : MealHistoryRepository {
        val calls = mutableListOf<String>()
        override fun readRevisions(id: String): MealRead { calls += id; return result }
    }

    @Test fun revisionsAreOrderedNewestFirstWithExactTextAbsenceAndZero() {
        val repository = Repository(MealRead.Loaded(listOf(second, third, first)))

        val history = assertIs<MealHistory.Loaded>(ReadMealHistory(repository).read("synthetic", MealKind.DRAFT))

        assertEquals(listOf(third, second, first), history.revisions)
        assertEquals(third, history.latest)
        assertEquals(DraftField.Entered("0"), history.revisions.last().content.carbs)
        assertEquals(DraftField.Missing, history.revisions.last().content.fat)
        assertEquals(DraftField.Entered("  exacto  "), history.revisions.last().content.notes)
        assertEquals(DraftField.Missing, history.latest.content.carbs)
        assertEquals(NutritionBasis.TOTAL_GRAMS, history.latest.content.basis)
        assertEquals(source, history.latest.copiedFrom)
        assertEquals("meal.history.read_only", history.blockCode)
        assertEquals(listOf("synthetic"), repository.calls)
    }

    @Test fun missingIdentityIsDistinctFromEveryFailureAndNothingAllowsClinicalUse() {
        val repository = Repository(MealRead.Loaded(emptyList()))
        val useCase = ReadMealHistory(repository)
        assertEquals(MealHistory.Missing, useCase.read("absent", MealKind.DISH))
        val states = mutableListOf<MealHistory>(MealHistory.Missing)
        MealFailure.entries.forEach {
            repository.result = MealRead.Failed(it)
            val failed = useCase.read("absent", MealKind.DISH)
            assertEquals(MealHistory.Failed(it), failed)
            assertNotEquals(MealHistory.Missing, failed)
            states += failed
        }
        repository.result = MealRead.Loaded(listOf(first))
        states += useCase.read("synthetic", MealKind.DRAFT)
        states.forEach { assertFalse(it.allowsCalculation); assertFalse(it.allowsTreatment) }
    }

    @Test fun blankIdentityIsRejectedWithoutReadingStorage() {
        val repository = Repository(MealRead.Loaded(listOf(first)))
        assertEquals(MealHistory.Failed(MealFailure.INVALID_RECORD), ReadMealHistory(repository).read(" ", MealKind.DRAFT))
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun gapsDuplicatesAndIdentityDriftFailClosedInsteadOfShowingPartialHistory() {
        val inconsistent = listOf(
            listOf(first, third),
            listOf(first, second, second),
            listOf(second, third),
            listOf(first, second.copy(id = "other")),
            listOf(first, second.copy(copiedFrom = null)),
            listOf(first.copy(kind = MealKind.DISH, copiedFrom = null)),
        )
        inconsistent.forEach { records ->
            assertEquals(MealHistory.Failed(MealFailure.INVALID_RECORD),
                ReadMealHistory(Repository(MealRead.Loaded(records))).read("synthetic", MealKind.DRAFT), "$records")
        }
        assertFailsWith<IllegalArgumentException> { MealHistory.Loaded("synthetic", MealKind.DRAFT, emptyList()) }
    }

    @Test fun corruptHugeRevisionFailsExplicitlyWithoutExpandingTheRange() {
        listOf(Long.MAX_VALUE, Int.MAX_VALUE.toLong() + 1, 50_000_000L).forEach { huge ->
            listOf(listOf(first.copy(revision = huge)), listOf(first, second, first.copy(revision = huge))).forEach { records ->
                assertEquals(MealHistory.Failed(MealFailure.INVALID_RECORD),
                    ReadMealHistory(Repository(MealRead.Loaded(records))).read("synthetic", MealKind.DRAFT))
            }
        }
    }
}
