package org.bolusai.mfp

import kotlin.test.*

class MfpDiaryInterpreterTest {
    @Test fun readsMealsFoodsAndExactTotals() {
        val reading = diary(page(mealRows("Breakfast", OATS, MILK)))
        assertEquals(DAY, reading.date)
        val meal = reading.meals.single()
        assertEquals("breakfast", meal.name)
        assertEquals(MfpMealId("mfp:2026-01-15:breakfast"), meal.id)
        assertEquals(MfpNutrients(known(39_500), known(5_000), known(11_000), known(4_000), known(240_000)),
            meal.totals)
        assertEquals(listOf("Synthetic oats, 40 g", "Synthetic milk, 1.5 cup (240 ml)"), meal.foods.map { it.name })
        assertEquals(listOf("Synthetic oats", "Synthetic milk"), meal.foods.map { it.shortName })
        assertEquals(listOf("40", "1.5"), meal.foods.map { it.quantity })
        assertEquals(listOf("g", "cup"), meal.foods.map { it.unit })
        assertEquals(known(12_500), meal.foods[1].nutrients.carbohydrates)
        assertEquals("12.500", (meal.foods[1].nutrients.carbohydrates as MfpAmount.Known).text)
    }

    @Test fun columnNamesAreLowercasedAndCarbsMeansCarbohydrates() {
        val rows = mealRows("LUNCH", food("Synthetic rice, 1 cup", "7", "8", "9"),
            columns = listOf("  FIBER ", "carbs", "Carbohydrates per portion"))
        val nutrients = diary(page(rows)).meals.single().foods.single().nutrients
        assertEquals(known(7_000), nutrients.fiber)
        assertEquals(known(8_000), nutrients.carbohydrates)
        assertEquals(unknown(MfpUnknown.COLUMN_ABSENT), nutrients.fat)
    }

    @Test fun columnsOfTheFirstHeaderApplyToEveryMeal() {
        val lunch = mealRows("Lunch", food("Synthetic bread, 2 slice", "160", "30", "2", "6", "3"),
            columns = listOf("Fat", "Fiber"))
        val meals = diary(page(mealRows("Breakfast", OATS), lunch)).meals
        assertEquals(known(30_000), meals[1].totals.carbohydrates)
        assertEquals(known(2_000), meals[1].totals.fat)
        assertEquals(known(160_000), meals[1].totals.calories)
    }

    @Test fun cellsBeyondTheColumnsAreIgnored() {
        val meal = diary(page(mealRows("Dinner", food("Synthetic soup, 1 bowl", "80", "10", "1", "2", "3", "x", "999"))))
            .meals.single()
        assertEquals(MfpNutrients(known(10_000), known(1_000), known(2_000), known(3_000), known(80_000)),
            meal.totals)
    }

    @Test fun mealsWithoutFoodsAreOmitted() {
        val meals = diary(page(mealRows("Breakfast"), mealRows("Lunch", OATS), mealRows("Snacks"))).meals
        assertEquals(listOf("lunch"), meals.map { it.name })
        assertTrue(diary(page(mealRows("Breakfast"))).meals.isEmpty())
    }

    @Test fun quantityAndUnitComeFromTheNameOrStayAbsent() {
        val foods = diary(page(mealRows("Dinner",
            food("Synthetic bread, wholemeal, 2 ración", "1", "1", "1", "1", "1"),
            food("Synthetic water", "0", "0", "0", "0", "0"),
            food("Synthetic stew, 1.25 serving(s) (300 g)", "1", "1", "1", "1", "1")))).meals.single().foods
        assertEquals(listOf("Synthetic bread, wholemeal", "Synthetic water", "Synthetic stew"),
            foods.map { it.shortName })
        assertEquals(listOf("2", null, "1.25"), foods.map { it.quantity })
        assertEquals(listOf("ración", null, "serving(s)"), foods.map { it.unit })
    }

    @Test fun numbersAreExactThousandthsAndUnreadableIsNeverZero() {
        assertEquals(known(0), MfpAmount.parse("0"))
        assertEquals(known(12_500), MfpAmount.parse(" 12.5 g"))
        assertEquals(known(1_234_000), MfpAmount.parse("1,234"))
        assertEquals(known(1_234_567_125), MfpAmount.parse("1,234,567.125"))
        assertEquals(known(7_000), MfpAmount.parse("007"))
        listOf("", " ", "n/a", "--".replace("-", "–"), "1.2.3", ".5", "5.", "1.2345", "1,5", "1,2345", ",500",
            "1234567890").forEach { assertEquals(unknown(MfpUnknown.UNREADABLE), MfpAmount.parse(it), it) }
        listOf("-5", "5-", "--", "-0").forEach { assertEquals(unknown(MfpUnknown.NEGATIVE), MfpAmount.parse(it), it) }
        assertFailsWith<IllegalArgumentException> { MfpAmount.Known(-1) }
    }

    @Test fun anUnconfiguredColumnLeavesTheNutrientUnknownInFoodsAndTotal() {
        val meal = diary(page(mealRows("Breakfast", food("Synthetic toast, 1 slice", "80", "15", "1", "3"),
            columns = listOf("Calories", "Carbs", "Fat", "Protein")))).meals.single()
        assertEquals(unknown(MfpUnknown.COLUMN_ABSENT), meal.foods.single().nutrients.fiber)
        assertEquals(unknown(MfpUnknown.COLUMN_ABSENT), meal.totals.fiber)
        assertEquals(known(15_000), meal.totals.carbohydrates)
    }

    @Test fun aRepeatedColumnIsAmbiguous() {
        val meal = diary(page(mealRows("Breakfast", food("Synthetic toast, 1 slice", "15", "16"),
            columns = listOf("Carbs", "Carbohydrates")))).meals.single()
        assertEquals(unknown(MfpUnknown.COLUMN_AMBIGUOUS), meal.totals.carbohydrates)
    }

    @Test fun aTotalIsUnknownWhenAnyFoodIsAndIsNeverAPartialSum() {
        val meal = diary(page(mealRows("Lunch", OATS,
            food("Synthetic sauce, 1 tbsp", "20", "?", "-1", "0"),
            MILK))).meals.single()
        assertEquals(unknown(MfpUnknown.UNREADABLE), meal.foods[1].nutrients.carbohydrates)
        assertEquals(unknown(MfpUnknown.NEGATIVE), meal.foods[1].nutrients.fat)
        assertEquals(unknown(MfpUnknown.CELL_ABSENT), meal.foods[1].nutrients.fiber)
        assertEquals(unknown(MfpUnknown.FOOD_UNKNOWN), meal.totals.carbohydrates)
        assertEquals(unknown(MfpUnknown.FOOD_UNKNOWN), meal.totals.fat)
        assertEquals(unknown(MfpUnknown.FOOD_UNKNOWN), meal.totals.fiber)
        assertEquals(known(11_000), meal.totals.protein)
        assertEquals(known(260_000), meal.totals.calories)
        assertFailsWith<IllegalArgumentException> { MfpAmount.total(emptyList()) }
    }

    @Test fun pagesThatAreNotTheDiaryFailWithTheirOwnCode() {
        val rows = mealRows("Breakfast", OATS)
        val cases = mapOf(
            page(rows, finalUrl = "https://www.myfitnesspal.com/account/ad_consents?next=diary") to
                MfpFailure.CONSENT_REQUIRED,
            page(rows, challengeMarker = true) to MfpFailure.CHALLENGE,
            page(rows, title = "MyFitnessPal | Challenge") to MfpFailure.CHALLENGE,
            page(rows, title = "Synthetic CAPTCHA page") to MfpFailure.CHALLENGE,
            page(rows, title = "MyFitnessPal | Blocked") to MfpFailure.BLOCKED,
            page(rows, lockedDiaryMarker = true) to MfpFailure.DIARY_LOCKED,
            page() to MfpFailure.UNEXPECTED_PAGE,
            page(rows, httpStatus = 401) to MfpFailure.SESSION_REJECTED,
            page(rows, httpStatus = 403) to MfpFailure.SESSION_REJECTED,
            page(rows, httpStatus = 429) to MfpFailure.RATE_LIMITED,
            page(rows, httpStatus = 500) to MfpFailure.SERVER_ERROR,
            page(rows, httpStatus = 503) to MfpFailure.SERVER_ERROR,
            page(rows, httpStatus = 404) to MfpFailure.UNEXPECTED_PAGE,
            page(rows, finalUrl = "https://www.myfitnesspal.com/food/diary") to MfpFailure.DATE_NOT_CONFIRMED,
            page(rows, finalUrl = "https://www.myfitnesspal.com/food/diary?date=2026-01-14") to
                MfpFailure.DATE_NOT_CONFIRMED,
            page(rows, finalUrl = "$DIARY_URL&date=2026-01-14") to MfpFailure.DATE_NOT_CONFIRMED,
            page(rows, finalUrl = "https://www.myfitnesspal.com/food/diary#?date=2026-01-15") to
                MfpFailure.DATE_NOT_CONFIRMED,
            page(mealRows("", OATS)) to MfpFailure.AMBIGUOUS_MEALS,
            page(MfpMealRows(emptyList(), listOf(OATS))) to MfpFailure.AMBIGUOUS_MEALS,
            page(mealRows("Lunch", OATS), mealRows(" lunch ", MILK)) to MfpFailure.AMBIGUOUS_MEALS,
        )
        cases.forEach { (page, expected) -> assertEquals(expected, failure(page), page.toString()) }
        assertEquals(MfpFailure.entries.size, MfpFailure.entries.map { it.code }.toSet().size)
    }

    @Test fun onlyTheMyFitnessPalHostOverHttpsIsRead() {
        val rows = mealRows("Breakfast", OATS)
        listOf(
            "http://www.myfitnesspal.com/food/diary?date=2026-01-15",
            "https://myfitnesspal.com/food/diary?date=2026-01-15",
            "https://api.myfitnesspal.com/food/diary?date=2026-01-15",
            "https://www.myfitnesspal.com.synthetic.invalid/food/diary?date=2026-01-15",
            "https://www.myfitnesspal.com@synthetic.invalid/food/diary?date=2026-01-15",
            "https://www.myfitnesspal.com\\@synthetic.invalid/food/diary?date=2026-01-15",
            "https://www.myfitnesspal.com:8443/food/diary?date=2026-01-15",
            "https://synthetic.invalid/?next=https://www.myfitnesspal.com/food/diary?date=2026-01-15",
            "www.myfitnesspal.com/food/diary?date=2026-01-15",
            "",
        ).forEach { assertEquals(MfpFailure.HOST_NOT_ALLOWED, failure(page(rows, finalUrl = it)), it) }
        // The host is checked before anything else the page says.
        assertEquals(MfpFailure.HOST_NOT_ALLOWED, failure(page(rows,
            finalUrl = "https://synthetic.invalid/account/ad_consents", challengeMarker = true)))
        listOf("HTTPS://WWW.MyFitnessPal.com/food/diary?date=2026-01-15",
            "https://www.myfitnesspal.com/es/food/diary?date=2026-01-15",
            "https://www.myfitnesspal.com/food/diary/synthetic-user?date=2026-01-15#top").forEach {
            assertEquals(1, diary(page(rows, finalUrl = it)).meals.size, it)
        }
    }

    @Test fun datesAreRealCalendarDays() {
        listOf("2026-01-15", "2024-02-29", "2000-02-29", "2026-12-31").forEach { assertNotNull(MfpDate.parse(it), it) }
        listOf("2026-1-15", "2026-02-29", "1900-02-29", "2026-13-01", "2026-04-31", "2026-00-10", "2026-01-00",
            "20260115", "2026-01-15 ", "2026/01/15", "abcd-ef-gh", "0000-01-01", "").forEach {
            assertNull(MfpDate.parse(it), it)
        }
    }
}
