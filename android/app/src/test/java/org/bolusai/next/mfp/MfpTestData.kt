package org.bolusai.next.mfp

import org.bolusai.mfp.MfpDate
import org.bolusai.mfp.MfpDiaryInterpreter
import org.bolusai.mfp.MfpDiaryPage
import org.bolusai.mfp.MfpDiaryReading
import org.bolusai.mfp.MfpFoodRow
import org.bolusai.mfp.MfpMeal
import org.bolusai.mfp.MfpMealRows
import org.bolusai.mfp.MfpSha256
import java.security.MessageDigest

/** Synthetic diary pages for the JVM tests of the link. No real food, user or session. */
internal object MfpTestData {
    val date: MfpDate = requireNotNull(MfpDate.parse("2026-01-15"))
    val url = "https://www.myfitnesspal.com/food/diary?date=2026-01-15"
    val header = listOf("Breakfast", "Calories", "Carbs", "Fat", "Protein", "Fiber")
    val interpreter = MfpDiaryInterpreter(MfpSha256 { text ->
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    })

    fun page(vararg foods: MfpFoodRow, header: List<String> = this.header): MfpDiaryPage =
        MfpDiaryPage(url, "Synthetic diary", false, false, listOf(MfpMealRows(header, foods.toList())), 200)

    fun meal(vararg foods: MfpFoodRow, header: List<String> = this.header): MfpMeal =
        (interpreter.interpret(date, page(*foods, header = header)) as MfpDiaryReading.Diary).meals.single()

    val oats = MfpFoodRow("Synthetic oats, 40 g", listOf("150", "27", "2.5", "5", "4"))
    val milk = MfpFoodRow("Synthetic milk, 200 ml", listOf("90", "9.6", "3", "6.2", "0"))
}
