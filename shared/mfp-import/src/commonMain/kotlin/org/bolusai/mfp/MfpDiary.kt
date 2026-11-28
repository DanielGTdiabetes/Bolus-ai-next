package org.bolusai.mfp

/**
 * Why a page is not a diary Next can read. Stable codes; they never carry values. Each one is a specific, visible
 * state: none of them is shown as "offline" and none lets an earlier reading stand in for the current one.
 */
enum class MfpFailure(val code: String) {
    /** The final address is not `https://www.myfitnesspal.com`. Nothing else of the page is looked at. */
    HOST_NOT_ALLOWED("mfp.page.host_not_allowed"),

    /** MyFitnessPal asks for the advertising consent; the user must answer it in the browser. */
    CONSENT_REQUIRED("mfp.page.consent_required"),

    /** Captcha or Cloudflare challenge. */
    CHALLENGE("mfp.page.challenge"),

    /** MyFitnessPal blocked the session, the address or the cookies. */
    BLOCKED("mfp.page.blocked"),

    /** HTTP 401 or 403. */
    SESSION_REJECTED("mfp.page.session_rejected"),

    /** HTTP 429. */
    RATE_LIMITED("mfp.page.rate_limited"),

    /** HTTP 500 or above. */
    SERVER_ERROR("mfp.page.server_error"),

    /** The diary is protected with a key. */
    DIARY_LOCKED("mfp.page.diary_locked"),

    /** The final address does not state the requested day, so the page cannot be attributed to it. */
    DATE_NOT_CONFIRMED("mfp.page.date_not_confirmed"),

    /** No meal header: the sign-in page or anything else that is not the diary. */
    UNEXPECTED_PAGE("mfp.page.unexpected"),

    /** A meal without a name or two meals with the same name: their identities would collide. */
    AMBIGUOUS_MEALS("mfp.page.ambiguous_meals"),

    /** The reading port could not load the page. Reported by the platform adapter, never by the interpreter. */
    TRANSPORT_FAILED("mfp.read.transport_failed"),
}

/** Injected SHA-256 so this module does not carry another implementation of it. */
fun interface MfpSha256 {
    /** Lowercase hexadecimal SHA-256 of the UTF-8 bytes of [text]. */
    fun hexOfUtf8(text: String): String
}

/** Identity of a diary meal, `mfp:<date>:<meal>`. Editing the meal in MyFitnessPal does not change it. */
data class MfpMealId(val text: String) {
    companion object {
        fun of(date: MfpDate, meal: String): MfpMealId = MfpMealId("mfp:${date.text}:$meal")
    }
}

/** SHA-256 of the canonical text of a meal. It changes whenever the meal content does. */
data class MfpRevision(val sha256: String) {
    init {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "The injected hash must return 64 lowercase hexadecimal characters"
        }
    }
}

data class MfpFood(
    /** Name as the page shows it. */
    val name: String,
    /** Name without the trailing quantity and unit; equal to [name] when they cannot be told apart. */
    val shortName: String,
    /** Quantity text as written in the name; `null` when the name does not state it. Never interpreted. */
    val quantity: String?,
    val unit: String?,
    val nutrients: MfpNutrients,
)

data class MfpMeal(
    val id: MfpMealId,
    val revision: MfpRevision,
    /** Meal name in lowercase, as MyFitnessPal names it. No meal time: MyFitnessPal gives none (ADR 0032). */
    val name: String,
    /** Sum of the foods. A nutrient is unknown when it is unknown in any food. */
    val totals: MfpNutrients,
    val foods: List<MfpFood>,
    /** The exact text whose SHA-256 is [revision]. */
    val canonicalText: String,
)

sealed interface MfpDiaryReading {
    /** Meals that have at least one food, in page order. */
    data class Diary(val date: MfpDate, val meals: List<MfpMeal>) : MfpDiaryReading

    data class Failed(val failure: MfpFailure) : MfpDiaryReading
}

/** How an imported meal relates to the revision already stored for its identity. */
enum class MfpImportKind { NEW_MEAL, NEW_REVISION, IDENTICAL }

/**
 * Interprets a diary page with the rules observed in the Legacy adapter (`myfitnesspal` 2.1.2, ADR 0032, section
 * "Adaptador del mini PC") and these safety differences: an unreadable or negative number is unknown, a column the
 * user has not configured leaves the nutrient unknown, and a meal total is unknown when any of its foods is. The
 * Legacy check "total against sum of foods, 1 g" is not adopted: here the total is the sum.
 */
class MfpDiaryInterpreter(private val sha256: MfpSha256) {
    fun interpret(date: MfpDate, page: MfpDiaryPage): MfpDiaryReading {
        failureOf(date, page)?.let { return MfpDiaryReading.Failed(it) }
        val names = page.meals.map { it.header.firstOrNull()?.trim()?.lowercase().orEmpty() }
        if (names.any { it.isEmpty() } || names.toSet().size != names.size) {
            return MfpDiaryReading.Failed(MfpFailure.AMBIGUOUS_MEALS)
        }
        // The Legacy library takes the columns of the first header for every meal.
        val columns = page.meals.first().header.drop(1).map(::columnName)
        val meals = page.meals.mapIndexedNotNull { index, rows ->
            if (rows.foods.isEmpty()) null else meal(date, names[index], columns, rows.foods)
        }
        return MfpDiaryReading.Diary(date, meals)
    }

    /** Distinguishes a new meal, a new revision of a known meal and an identical re-import. */
    fun compare(stored: MfpRevision?, read: MfpMeal): MfpImportKind = when (stored) {
        null -> MfpImportKind.NEW_MEAL
        read.revision -> MfpImportKind.IDENTICAL
        else -> MfpImportKind.NEW_REVISION
    }

    private fun failureOf(date: MfpDate, page: MfpDiaryPage): MfpFailure? {
        val title = page.title.lowercase()
        val status = page.httpStatus
        return when {
            !hostAllowed(page.finalUrl) -> MfpFailure.HOST_NOT_ALLOWED
            page.finalUrl.contains("/account/ad_consents") -> MfpFailure.CONSENT_REQUIRED
            page.challengeMarker || title.contains("myfitnesspal | challenge") || title.contains("captcha") ->
                MfpFailure.CHALLENGE
            title.contains("myfitnesspal | blocked") -> MfpFailure.BLOCKED
            status == 401 || status == 403 -> MfpFailure.SESSION_REJECTED
            status == 429 -> MfpFailure.RATE_LIMITED
            status != null && status >= 500 -> MfpFailure.SERVER_ERROR
            page.lockedDiaryMarker -> MfpFailure.DIARY_LOCKED
            status != null && status !in 200..299 -> MfpFailure.UNEXPECTED_PAGE
            page.meals.isEmpty() -> MfpFailure.UNEXPECTED_PAGE
            !dateConfirmed(date, page.finalUrl) -> MfpFailure.DATE_NOT_CONFIRMED
            else -> null
        }
    }

    private fun meal(date: MfpDate, name: String, columns: List<String>, rows: List<MfpFoodRow>): MfpMeal {
        val foods = rows.map { row ->
            val nutrients = MfpNutrients.of { nutrient -> amount(nutrient, columns, row.cells) }
            val parts = QUANTITY_AND_UNIT.find(row.name)
            MfpFood(
                name = row.name,
                shortName = parts?.groupValues?.get(1) ?: row.name,
                quantity = parts?.groupValues?.get(2),
                unit = parts?.groupValues?.get(3),
                nutrients = nutrients,
            )
        }
        val totals = MfpNutrients.of { nutrient -> MfpAmount.total(foods.map { it.nutrients[nutrient] }) }
        val canonical = canonicalText(date, name, totals, foods)
        return MfpMeal(MfpMealId.of(date, name), MfpRevision(sha256.hexOfUtf8(canonical)), name, totals, foods,
            canonical)
    }

    private fun amount(nutrient: MfpNutrient, columns: List<String>, cells: List<String>): MfpAmount {
        val positions = columns.indices.filter { columns[it] == nutrient.column }
        return when {
            positions.isEmpty() -> MfpAmount.Unknown(MfpUnknown.COLUMN_ABSENT)
            positions.size > 1 -> MfpAmount.Unknown(MfpUnknown.COLUMN_AMBIGUOUS)
            else -> cells.getOrNull(positions.single())?.let(MfpAmount::parse)
                ?: MfpAmount.Unknown(MfpUnknown.CELL_ABSENT)
        }
    }

    companion object {
        const val HOST = "www.myfitnesspal.com"

        /** Version of the canonical text. A change of format is a new version, never a silent change. */
        const val CANONICAL_VERSION = "mfp-meal-revision-v1"

        /**
         * Quantity and unit at the end of a food name, as written in ADR 0032. Python's `\w` is Unicode, so letters
         * and digits are spelled out here to keep units such as `ración` whole on every platform.
         */
        private val QUANTITY_AND_UNIT = Regex("""(.+), (\d[\d.]*) ([\p{L}\p{N}_()]+)(?: \(.*\))?""")

        private fun columnName(cell: String): String =
            cell.trim().lowercase().let { if (it == "carbs") "carbohydrates" else it }

        /** HTTPS and exactly the MyFitnessPal host: no user information, no port, no other subdomain. */
        private fun hostAllowed(url: String): Boolean {
            val scheme = "https://"
            if (!url.startsWith(scheme, ignoreCase = true)) return false
            val authority = url.substring(scheme.length).takeWhile { it != '/' && it != '?' && it != '#' }
            return authority.lowercase() == HOST
        }

        /** The query must state `date=` and every such parameter must be the requested day. */
        private fun dateConfirmed(date: MfpDate, url: String): Boolean {
            val query = url.substringBefore('#').substringAfter('?', "")
            val dates = query.split('&').filter { it.startsWith("date=") }.map { it.removePrefix("date=") }
            return dates.isNotEmpty() && dates.all { it == date.text }
        }

        /**
         * One fact per line, in a fixed order, with `\`, line feed and carriage return escaped so that no text can
         * imitate another line. Unknown values are written as `unknown`, never as a number.
         */
        fun canonicalText(date: MfpDate, meal: String, totals: MfpNutrients, foods: List<MfpFood>): String {
            val lines = mutableListOf(CANONICAL_VERSION, "date=${date.text}", "meal=${escape(meal)}")
            MfpNutrient.entries.forEach { lines += "total.${it.column}=${text(totals[it])}" }
            lines += "foods=${foods.size}"
            foods.forEachIndexed { index, food ->
                lines += "food.$index.name=${escape(food.name)}"
                lines += "food.$index.quantity${optional(food.quantity)}"
                lines += "food.$index.unit${optional(food.unit)}"
                MfpNutrient.entries.forEach { lines += "food.$index.${it.column}=${text(food.nutrients[it])}" }
            }
            return lines.joinToString("\n", postfix = "\n")
        }

        private fun text(amount: MfpAmount): String = when (amount) {
            is MfpAmount.Known -> amount.text
            is MfpAmount.Unknown -> "unknown"
        }

        private fun optional(value: String?): String = if (value == null) ".absent" else "=${escape(value)}"

        private fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    }
}
