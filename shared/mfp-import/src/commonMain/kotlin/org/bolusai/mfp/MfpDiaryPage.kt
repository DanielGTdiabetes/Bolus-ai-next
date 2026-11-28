package org.bolusai.mfp

/**
 * What one load of the MyFitnessPal diary page brought, as uninterpreted text (ADR 0032). The platform adapter fills
 * it from the page it loaded; nothing here is parsed, trimmed, lowercased or converted. This module never opens a
 * connection: it only interprets what it is handed.
 *
 * Nutrition read from MyFitnessPal is declared by a third party. It becomes a reviewable meal draft and never enters
 * a calculation without the user's review.
 */
data class MfpDiaryPage(
    /** Address of the page after every redirect. */
    val finalUrl: String,
    /** Text of the page title. */
    val title: String,
    /** True when the page source contains `cf-challenge`. */
    val challengeMarker: Boolean,
    /** True when the page text contains `diary is locked with a key`. */
    val lockedDiaryMarker: Boolean,
    /** One entry per `tr.meal_header`, in page order. */
    val meals: List<MfpMealRows>,
    /** HTTP status of the page when the platform knows it; `null` when it does not. Never assumed to be 200. */
    val httpStatus: Int? = null,
)

/**
 * A meal as the table shows it.
 *
 * [header] holds the leading text of every cell of the `meal_header` row (the text before any child element, which is
 * what the Legacy library reads): the first is the meal name and the rest are the column names the user configured.
 * [foods] are the following rows without a `class` attribute, up to the first row that has one.
 */
data class MfpMealRows(val header: List<String>, val foods: List<MfpFoodRow>)

/**
 * A food row. [name] is the text of the link in the first cell. [cells] are the texts of the cells after the first,
 * in order: the text of `span.macro-value` when the cell has children, otherwise the cell text. Trailing cells beyond
 * the columns (the delete button) may be present and are ignored.
 */
data class MfpFoodRow(val name: String, val cells: List<String>)

/** A calendar day of the diary, `YYYY-MM-DD`. Only real dates of the proleptic Gregorian calendar are accepted. */
class MfpDate private constructor(val text: String) {
    override fun equals(other: Any?): Boolean = other is MfpDate && other.text == text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = text

    companion object {
        fun parse(text: String): MfpDate? {
            if (text.length != 10 || text[4] != '-' || text[7] != '-') return null
            if (text.indices.any { it != 4 && it != 7 && text[it] !in '0'..'9' }) return null
            val year = text.substring(0, 4).toInt()
            val month = text.substring(5, 7).toInt()
            val day = text.substring(8, 10).toInt()
            if (year < 1 || month !in 1..12) return null
            val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
            val days = when (month) {
                2 -> if (leap) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
            return if (day in 1..days) MfpDate(text) else null
        }
    }
}
