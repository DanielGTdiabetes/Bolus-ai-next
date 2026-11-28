package org.bolusai.mfp

/** Synthetic data only: no page, food or value here comes from a real diary. */
internal val DAY: MfpDate = MfpDate.parse("2026-01-15")!!

internal const val DIARY_URL = "https://www.myfitnesspal.com/food/diary?date=2026-01-15"

internal val COLUMNS = listOf("Calories", "Carbs", "Fat", "Protein", "Fiber")

/**
 * Deterministic stand-in for the injected SHA-256: four FNV-1a passes with different seeds, 64 hexadecimal
 * characters. The module never computes a hash itself, so the tests only need a function of the text.
 */
internal object FakeSha256 : MfpSha256 {
    val texts = mutableListOf<String>()

    override fun hexOfUtf8(text: String): String {
        texts += text
        return (1..4).joinToString("") { seed ->
            var hash = -3750763034362895579L + seed
            text.forEach { hash = (hash xor it.code.toLong()) * 1099511628211L }
            hash.toULong().toString(16).padStart(16, '0')
        }
    }
}

internal val interpreter = MfpDiaryInterpreter(FakeSha256)

internal fun mealRows(name: String, vararg foods: MfpFoodRow, columns: List<String> = COLUMNS): MfpMealRows =
    MfpMealRows(listOf(name) + columns, foods.toList())

internal fun food(name: String, vararg cells: String): MfpFoodRow = MfpFoodRow(name, cells.toList())

internal fun page(
    vararg meals: MfpMealRows,
    finalUrl: String = DIARY_URL,
    title: String = "Synthetic food diary | MyFitnessPal",
    challengeMarker: Boolean = false,
    lockedDiaryMarker: Boolean = false,
    httpStatus: Int? = null,
    declaredDates: List<String> = emptyList(),
): MfpDiaryPage =
    MfpDiaryPage(finalUrl, title, challengeMarker, lockedDiaryMarker, meals.toList(), httpStatus, declaredDates)

internal fun diary(page: MfpDiaryPage): MfpDiaryReading.Diary =
    interpreter.interpret(DAY, page) as MfpDiaryReading.Diary

internal fun failure(page: MfpDiaryPage): MfpFailure =
    (interpreter.interpret(DAY, page) as MfpDiaryReading.Failed).failure

internal fun known(thousandths: Long): MfpAmount = MfpAmount.Known(thousandths)

internal fun unknown(reason: MfpUnknown): MfpAmount = MfpAmount.Unknown(reason)

internal val OATS = food("Synthetic oats, 40 g", "150", "27", "3", "5", "4")
internal val MILK = food("Synthetic milk, 1.5 cup (240 ml)", "90", "12.5", "2", "6", "0")
