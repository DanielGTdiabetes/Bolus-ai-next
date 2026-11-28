package org.bolusai.next.mfp

import org.bolusai.mfp.MfpDiaryPage
import org.bolusai.mfp.MfpDiaryReading
import org.bolusai.mfp.MfpFoodRow
import org.bolusai.mfp.MfpMealRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0032: the answer of the extraction script is read strictly; a partial or odd answer is not a page. */
class MfpExtractionTest {
    /** What the view hands back: the lines as one JSON string literal. */
    private fun answer(vararg lines: String): String =
        "\"" + lines.joinToString("\\n").replace("\"", "\\\"") + "\""

    private val start = arrayOf("mfp-extract-v2", "url https%3A%2F%2Fwww.myfitnesspal.com%2Ffood%2Fdiary%3Fdate%3D2026-01-15",
        "title Synthetic%20diary", "challenge 0", "locked 0")

    @Test fun readsMealsFoodsAndCellsAsUninterpretedText() {
        val page = MfpExtraction.decode(answer(*start,
            "meal", "h Breakfast", "h Calories", "h Carbs",
            "food Synthetic%20oats%2C%2040%20g", "c 150", "c 27", "c ",
            "food Raci%C3%B3n%20sint%C3%A9tica", "c 1%2C234", "c %20n%2Fa%20",
            "meal", "h Dinner", "h Calories", "h Carbs",
            "end"), 200)

        assertEquals(MfpDiaryPage(MfpTestData.url, "Synthetic diary", false, false, listOf(
            MfpMealRows(listOf("Breakfast", "Calories", "Carbs"), listOf(
                MfpFoodRow("Synthetic oats, 40 g", listOf("150", "27", "")),
                MfpFoodRow("Ración sintética", listOf("1,234", " n/a ")))),
            MfpMealRows(listOf("Dinner", "Calories", "Carbs"), emptyList())), 200), page)
    }

    @Test fun decodedPageIsInterpretedByTheSharedModule() {
        val page = requireNotNull(MfpExtraction.decode(answer(*start,
            "meal", "h Breakfast", "h Calories", "h Carbs", "h Fat", "h Protein", "h Fiber",
            "food Synthetic%20oats%2C%2040%20g", "c 150", "c 27", "c 2.5", "c 5", "c 4", "c ", "end"), null))

        val meal = (MfpTestData.interpreter.interpret(MfpTestData.date, page) as MfpDiaryReading.Diary).meals.single()

        assertEquals(MfpTestData.meal(MfpTestData.oats).revision, meal.revision)
        assertNull(page.httpStatus)
    }

    @Test fun theDayThePageDeclaresIsPassedAsWritten() {
        val today = "url https%3A%2F%2Fwww.myfitnesspal.com%2Ffood%2Fdiary"
        val page = requireNotNull(MfpExtraction.decode(answer("mfp-extract-v2", today, start[2], start[3], start[4],
            "day 2026-01-15", "meal", "h Breakfast", "h Calories", "h Carbs", "h Fat", "h Protein", "h Fiber",
            "food Synthetic%20oats%2C%2040%20g", "c 150", "c 27", "c 2.5", "c 5", "c 4", "end"), null))

        assertEquals(listOf("2026-01-15"), page.declaredDates)
        // The address has no date: the page's own statement is what attributes it to the day.
        val meal = (MfpTestData.interpreter.interpret(MfpTestData.date, page) as MfpDiaryReading.Diary).meals.single()
        assertEquals("mfp:2026-01-15:breakfast", meal.id.text)
        assertEquals(listOf("", "x"), requireNotNull(MfpExtraction.decode(
            answer(*start, "day ", "day x", "end"), null)).declaredDates)
        assertTrue(requireNotNull(MfpExtraction.decode(answer(*start, "end"), null)).declaredDates.isEmpty())
    }

    @Test fun flagsAndStatusArePassedThroughNeverAssumed() {
        val page = requireNotNull(MfpExtraction.decode(answer("mfp-extract-v2", "url https%3A%2F%2Fexample.org%2F",
            "title Just%20a%20moment", "challenge 1", "locked 1", "end"), 403))
        assertTrue(page.challengeMarker && page.lockedDiaryMarker)
        assertEquals(403, page.httpStatus)
        assertTrue(page.meals.isEmpty())
        assertNull(requireNotNull(MfpExtraction.decode(answer(*start, "end"), null)).httpStatus)
    }

    @Test fun anythingThatIsNotTheExpectedAnswerIsNotAPage() {
        val bad = listOf(
            null, "null", "", "\"\"", "{}", "mfp-extract-v1",
            answer(*start),                                                    // no end
            answer("mfp-extract-v1", *start.drop(1).toTypedArray(), "end"),    // another version
            answer(*start, "meal", "h A", "day 2026-01-15", "end"),            // the day after a meal
            answer(*start, "h Breakfast", "end"),                              // header cell without meal
            answer(*start, "food x", "end"),                                   // food without meal
            answer(*start, "meal", "c 1", "end"),                              // cell without food
            answer(*start, "meal", "h A", "food x", "h B", "end"),             // header cell after a food
            answer(*start, "meal", "surprise", "end"),                         // unknown line
            answer(*start, "meal", "h a b", "end"),                            // raw space: not encodeURIComponent output
            answer(*start, "meal", "h %zz", "end"),                            // not hexadecimal
            answer(*start, "meal", "h %C3", "end"),                            // truncated UTF-8
            answer(*start, "meal", "h %ff", "end"),                            // invalid UTF-8
            answer(*start, "meal", "h %4", "end"),                             // truncated escape
            answer("mfp-extract-v2", start[1], start[2], "challenge 2", "locked 0", "end"),
            answer("mfp-extract-v2", start[2], start[1], "challenge 0", "locked 0", "end"),
            answer(*start, "end").dropLast(1),                                 // not a closed string literal
        )
        bad.forEach { assertNull(it.toString(), MfpExtraction.decode(it, 200)) }
    }

    @Test fun jsonStringLiteralIsDecodedStrictly() {
        assertEquals("a\nb\t\"c\"\\ é/", MfpExtraction.jsonString("\"a\\nb\\t\\\"c\\\"\\\\ \\u00e9\\/\""))
        assertEquals("", MfpExtraction.jsonString("\"\""))
        listOf("\"a\"b\"", "\"a\\x\"", "\"a\\u12\"", "\"a\\\"", "a", "\"", "\"a\nb\"").forEach {
            assertNull(it, MfpExtraction.jsonString(it))
        }
    }

    @Test fun percentDecodingAcceptsOnlyWhatEncodeUriComponentWrites() {
        assertEquals("ración (1) ~ * ' ! - _ .", MfpExtraction.percentDecode("raci%C3%B3n%20(1)%20~%20*%20'%20!%20-%20_%20."))
        assertEquals("", MfpExtraction.percentDecode(""))
        listOf("a+b", "a b", "a/b", "%", "%4", "%G0", "%E2%82").forEach { assertNull(it, MfpExtraction.percentDecode(it)) }
    }

    @Test fun scriptOnlyReadsThePage() {
        val script = MfpExtraction.SCRIPT
        assertTrue(script.contains("tr.meal_header") && script.contains("span.macro-value") && script.contains("cf-challenge"))
        assertTrue(script.contains("input#date_selector"))
        listOf("fetch(", "XMLHttpRequest", "sendBeacon", "document.cookie", "localStorage", "sessionStorage", ".submit(",
            ".click(", "innerHTML =", "location.href =", "location.assign", "eval(", "postMessage").forEach {
            assertFalse(it, script.contains(it))
        }
    }
}
