package org.bolusai.mfp

import kotlin.test.*

class ReadStableMfpDiaryTest {
    private val pauses = mutableListOf<Long>()
    private var loads = 0

    private val lunch = mealRows("Lunch", OATS)
    private fun dinner(carbs: String) = mealRows("Dinner", food("Synthetic pasta, 1 plate", "300", carbs, "5", "10", "4"))

    private fun useCase(vararg sequence: MfpPageLoad): ReadStableMfpDiary {
        val pending = sequence.toMutableList()
        return ReadStableMfpDiary(
            reader = { date ->
                assertEquals(DAY, date)
                loads++
                pending.removeAt(0)
            },
            pause = { pauses += it },
            interpreter = interpreter,
        )
    }

    private fun loaded(vararg meals: MfpMealRows): MfpPageLoad = MfpPageLoad.Loaded(page(*meals))

    @Test fun twoEqualLoadsAreStableAfterOnePause() {
        val result = useCase(loaded(lunch, dinner("60")), loaded(lunch, dinner("60"))).read(DAY)
            as MfpStableReading.Stable
        assertEquals(DAY, result.date)
        assertEquals(listOf("lunch", "dinner"), result.meals.map { it.name })
        assertTrue(result.changed.isEmpty())
        assertEquals(2, result.loads)
        assertEquals(2, loads)
        assertEquals(listOf(4_000L), pauses)
        assertEquals(4_000L, ReadStableMfpDiary.PAUSE_BETWEEN_LOADS_MS)
        assertEquals(3, ReadStableMfpDiary.MAX_LOADS)
    }

    @Test fun aChangeBetweenTheFirstTwoLoadsNeedsAThirdThatAgreesWithTheSecond() {
        val result = useCase(loaded(lunch, dinner("60")), loaded(lunch, dinner("75")), loaded(lunch, dinner("75")))
            .read(DAY) as MfpStableReading.Stable
        assertEquals(listOf("lunch", "dinner"), result.meals.map { it.name })
        assertEquals(known(75_000), result.meals[1].totals.carbohydrates)
        assertTrue(result.changed.isEmpty())
        assertEquals(3, result.loads)
        assertEquals(listOf(4_000L, 4_000L), pauses)
    }

    @Test fun aMealStillChangingAtTheThirdLoadIsReportedAndNotImported() {
        val result = useCase(loaded(lunch, dinner("60")), loaded(lunch, dinner("75")), loaded(lunch, dinner("90")))
            .read(DAY) as MfpStableReading.Stable
        assertEquals(listOf("lunch"), result.meals.map { it.name })
        assertEquals(listOf(MfpMealId("mfp:2026-01-15:dinner")), result.changed)
        assertEquals(3, result.loads)
        assertEquals(3, loads)
    }

    @Test fun mealsAddedOrRemovedBetweenTheLastTwoLoadsAreChanged() {
        val added = useCase(loaded(lunch), loaded(lunch, dinner("60")), loaded(lunch, dinner("60"), mealRows("Snacks", MILK)))
            .read(DAY) as MfpStableReading.Stable
        assertEquals(listOf("lunch", "dinner"), added.meals.map { it.name })
        assertEquals(listOf(MfpMealId("mfp:2026-01-15:snacks")), added.changed)

        val removed = useCase(loaded(lunch), loaded(lunch, dinner("60")), loaded(lunch)).read(DAY)
            as MfpStableReading.Stable
        assertEquals(listOf("lunch"), removed.meals.map { it.name })
        assertEquals(listOf(MfpMealId("mfp:2026-01-15:dinner")), removed.changed)
    }

    @Test fun aMealSeenOnlyInTheFirstLoadIsNeitherImportedNorReused() {
        val result = useCase(loaded(lunch, dinner("60")), loaded(lunch), loaded(lunch)).read(DAY)
            as MfpStableReading.Stable
        assertEquals(listOf("lunch"), result.meals.map { it.name })
        assertTrue(result.changed.isEmpty())
    }

    @Test fun anEmptyDiaryIsStableAndEmptyNotAFailure() {
        val result = useCase(loaded(mealRows("Breakfast")), loaded(mealRows("Breakfast"))).read(DAY)
            as MfpStableReading.Stable
        assertTrue(result.meals.isEmpty())
        assertTrue(result.changed.isEmpty())
    }

    @Test fun aFailedLoadReturnsNothingFromTheLoadsBeforeIt() {
        assertEquals(MfpStableReading.Failed(MfpFailure.TRANSPORT_FAILED, 1),
            useCase(MfpPageLoad.Failed(MfpFailure.TRANSPORT_FAILED)).read(DAY))
        assertTrue(pauses.isEmpty())
        assertEquals(MfpStableReading.Failed(MfpFailure.CHALLENGE, 2),
            useCase(loaded(lunch), MfpPageLoad.Loaded(page(lunch, challengeMarker = true))).read(DAY))
        assertEquals(MfpStableReading.Failed(MfpFailure.UNEXPECTED_PAGE, 3),
            useCase(loaded(lunch), loaded(lunch, dinner("60")), MfpPageLoad.Loaded(page())).read(DAY))
    }

    @Test fun everyCallLoadsAgainAndAnIdenticalReimportKeepsIdentityAndRevision() {
        val useCase = useCase(loaded(lunch, dinner("60")), loaded(lunch, dinner("60")),
            loaded(lunch, dinner("60")), loaded(lunch, dinner("60")))
        val first = useCase.read(DAY) as MfpStableReading.Stable
        assertEquals(2, loads)
        val second = useCase.read(DAY) as MfpStableReading.Stable
        assertEquals(4, loads)
        assertEquals(first.meals.map { it.id to it.revision }, second.meals.map { it.id to it.revision })
        first.meals.zip(second.meals).forEach { (stored, read) ->
            assertEquals(MfpImportKind.IDENTICAL, interpreter.compare(stored.revision, read))
        }
    }

    @Test fun aLaterCallNeverFallsBackToAnEarlierStableReading() {
        val useCase = useCase(loaded(lunch), loaded(lunch), MfpPageLoad.Failed(MfpFailure.TRANSPORT_FAILED))
        assertTrue(useCase.read(DAY) is MfpStableReading.Stable)
        assertEquals(MfpStableReading.Failed(MfpFailure.TRANSPORT_FAILED, 1), useCase.read(DAY))
    }
}
