package org.bolusai.mfp

import kotlin.test.*

class MfpRevisionTest {
    @Test fun theRevisionIsTheInjectedHashOfTheCanonicalText() {
        val meal = diary(page(mealRows("Breakfast", OATS,
            food("Synthetic sauce", "20", "?", "1", "0"),
            columns = listOf("Calories", "Carbs", "Fat", "Protein")))).meals.single()
        val expected = """
            mfp-meal-revision-v1
            date=2026-01-15
            meal=breakfast
            total.carbohydrates=unknown
            total.fat=4.000
            total.protein=5.000
            total.fiber=unknown
            total.calories=170.000
            foods=2
            food.0.name=Synthetic oats, 40 g
            food.0.quantity=40
            food.0.unit=g
            food.0.carbohydrates=27.000
            food.0.fat=3.000
            food.0.protein=5.000
            food.0.fiber=unknown
            food.0.calories=150.000
            food.1.name=Synthetic sauce
            food.1.quantity.absent
            food.1.unit.absent
            food.1.carbohydrates=unknown
            food.1.fat=1.000
            food.1.protein=0.000
            food.1.fiber=unknown
            food.1.calories=20.000

        """.trimIndent()
        assertEquals(expected, meal.canonicalText)
        assertEquals(MfpRevision(FakeSha256.hexOfUtf8(expected)), meal.revision)
        assertTrue(expected in FakeSha256.texts)
    }

    @Test fun theSameMealHasTheSameIdentityAndRevision() {
        val first = diary(page(mealRows("Breakfast", OATS, MILK))).meals.single()
        val second = diary(page(mealRows(" BREAKFAST ", OATS, MILK), title = "Another synthetic title")).meals.single()
        assertEquals(first.id, second.id)
        assertEquals(first.revision, second.revision)
        assertEquals(MfpImportKind.IDENTICAL, interpreter.compare(first.revision, second))
    }

    @Test fun changingAFoodChangesTheRevisionButNotTheIdentity() {
        val original = diary(page(mealRows("Breakfast", OATS, MILK))).meals.single()
        val variants = listOf(
            mealRows("Breakfast", OATS, food("Synthetic milk, 1.5 cup (240 ml)", "90", "13", "2", "6", "0")),
            mealRows("Breakfast", OATS, food("Synthetic milk, 2 cup (240 ml)", "90", "12.5", "2", "6", "0")),
            mealRows("Breakfast", OATS, food("Synthetic milk, 1.5 cup (240 ml)", "90", "?", "2", "6", "0")),
            mealRows("Breakfast", OATS),
            mealRows("Breakfast", MILK, OATS),
            mealRows("Breakfast", OATS, MILK, columns = listOf("Calories", "Carbs", "Fat", "Protein")),
        ).map { diary(page(it)).meals.single() }
        variants.forEach {
            assertEquals(original.id, it.id)
            assertNotEquals(original.revision, it.revision)
            assertEquals(MfpImportKind.NEW_REVISION, interpreter.compare(original.revision, it))
        }
        assertEquals(variants.size, variants.map { it.revision }.toSet().size)
        assertEquals(MfpImportKind.NEW_MEAL, interpreter.compare(null, original))
    }

    @Test fun dayAndMealNameArePartOfIdentityAndRevision() {
        val breakfast = diary(page(mealRows("Breakfast", OATS))).meals.single()
        val lunch = diary(page(mealRows("Lunch", OATS))).meals.single()
        val otherDay = MfpDate.parse("2026-01-16")!!
        val tomorrow = (interpreter.interpret(otherDay, page(mealRows("Breakfast", OATS),
            finalUrl = "https://www.myfitnesspal.com/food/diary?date=2026-01-16")) as MfpDiaryReading.Diary)
            .meals.single()
        assertEquals(MfpMealId("mfp:2026-01-16:breakfast"), tomorrow.id)
        assertEquals(3, setOf(breakfast.id, lunch.id, tomorrow.id).size)
        assertEquals(3, setOf(breakfast.revision, lunch.revision, tomorrow.revision).size)
    }

    @Test fun textCannotImitateAnotherLineOfTheCanonicalText() {
        val injected = diary(page(mealRows("Breakfast",
            food("Synthetic a\nfood.0.unit=g", "1", "1", "1", "1", "1")))).meals.single()
        assertTrue("food.0.name=Synthetic a\\nfood.0.unit=g\n" in injected.canonicalText)
        val escaped = diary(page(mealRows("Breakfast",
            food("Synthetic a\\nfood.0.unit=g", "1", "1", "1", "1", "1")))).meals.single()
        assertNotEquals(injected.revision, escaped.revision)
    }

    @Test fun aHashThatIsNotSha256ShapedIsRejected() {
        listOf("", "abc", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65)).forEach {
            assertFailsWith<IllegalArgumentException>(it) { MfpRevision(it) }
        }
        assertFailsWith<IllegalArgumentException> {
            MfpDiaryInterpreter { "not a hash" }.interpret(DAY, page(mealRows("Breakfast", OATS)))
        }
    }
}
