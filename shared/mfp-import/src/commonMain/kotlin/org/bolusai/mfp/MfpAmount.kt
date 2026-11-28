package org.bolusai.mfp

/** Why a value of the diary is not known. Stable codes; they never carry values. */
enum class MfpUnknown(val code: String) {
    /** The user has not configured that column in MyFitnessPal, so the page does not show the nutrient. */
    COLUMN_ABSENT("mfp.value.column_absent"),

    /** The first header names the column more than once, so the cell that holds the nutrient is ambiguous. */
    COLUMN_AMBIGUOUS("mfp.value.column_ambiguous"),

    /** The food row has fewer cells than the column position. */
    CELL_ABSENT("mfp.value.cell_absent"),

    /** The cell text is not a plain decimal number this module can represent exactly. */
    UNREADABLE("mfp.value.unreadable"),

    /** The cell text carries a minus sign. */
    NEGATIVE("mfp.value.negative"),

    /** A meal total where at least one food has that nutrient unknown. Never a partial sum. */
    FOOD_UNKNOWN("mfp.value.food_unknown"),
}

/**
 * A nutrient value as read from the diary: an exact decimal in thousandths, or unknown with its reason. Unknown is
 * never `0` (AGENTS.md section 3). Grams for the macronutrients and kilocalories for energy are the units the
 * MyFitnessPal table declares; nothing is converted.
 */
sealed interface MfpAmount {
    /** [thousandths] is the value multiplied by 1000, never negative. */
    data class Known(val thousandths: Long) : MfpAmount {
        init {
            require(thousandths >= 0) { "A known amount is never negative" }
        }

        /** Canonical text with exactly three decimals, for example `12.500`. */
        val text: String
            get() = "${thousandths / 1000}.${(thousandths % 1000).toString().padStart(3, '0')}"
    }

    data class Unknown(val reason: MfpUnknown) : MfpAmount

    companion object {
        /** Representation bound of the integer part, not a clinical limit. It keeps every sum far inside `Long`. */
        const val MAX_INTEGER_DIGITS = 9

        /**
         * Reads a cell as the Legacy library does (drop every character except digits, `-` and `.`), with the safety
         * differences of ADR 0032: what the library turns into `0` is unknown here.
         *
         * - A minus sign anywhere is [MfpUnknown.NEGATIVE].
         * - What remains must be digits with at most one `.` between digits, at most [MAX_INTEGER_DIGITS] integer and
         *   three fractional digits; otherwise [MfpUnknown.UNREADABLE].
         * - A comma is accepted only as a thousands separator (between digits, followed by exactly three digits).
         *   Dropping any other comma, as the library does, would turn `1,5` into `15`.
         */
        fun parse(cell: String): MfpAmount {
            if (!commasAreThousandsSeparators(cell)) return Unknown(MfpUnknown.UNREADABLE)
            val kept = cell.filter { it in '0'..'9' || it == '-' || it == '.' }
            if ('-' in kept) return Unknown(MfpUnknown.NEGATIVE)
            val parts = kept.split('.')
            if (parts.size > 2 || parts.any { it.isEmpty() }) return Unknown(MfpUnknown.UNREADABLE)
            val integer = parts[0].trimStart('0').ifEmpty { "0" }
            val fraction = if (parts.size == 2) parts[1] else ""
            if (integer.length > MAX_INTEGER_DIGITS || fraction.length > 3) return Unknown(MfpUnknown.UNREADABLE)
            return Known(integer.toLong() * 1000 + fraction.padEnd(3, '0').toLong())
        }

        private fun commasAreThousandsSeparators(cell: String): Boolean {
            for (index in cell.indices) {
                if (cell[index] != ',') continue
                val digitBefore = index > 0 && cell[index - 1] in '0'..'9'
                val group = cell.substring(index + 1).takeWhile { it in '0'..'9' }
                if (!digitBefore || group.length != 3) return false
            }
            return true
        }

        /** Exact sum. Unknown as soon as one term is unknown: a column absent stays absent, anything else is
         *  [MfpUnknown.FOOD_UNKNOWN]. An empty list is not a total and is rejected. */
        fun total(amounts: List<MfpAmount>): MfpAmount {
            require(amounts.isNotEmpty()) { "A total needs at least one food" }
            var sum = 0L
            for (amount in amounts) {
                when (amount) {
                    is Known -> sum += amount.thousandths
                    is Unknown -> return when (amount.reason) {
                        MfpUnknown.COLUMN_ABSENT, MfpUnknown.COLUMN_AMBIGUOUS -> amount
                        else -> Unknown(MfpUnknown.FOOD_UNKNOWN)
                    }
                }
            }
            return Known(sum)
        }
    }
}

/** The nutrients Next reads from the diary. Other columns of the page are ignored. */
enum class MfpNutrient(val column: String) {
    CARBOHYDRATES("carbohydrates"), FAT("fat"), PROTEIN("protein"), FIBER("fiber"), CALORIES("calories"),
}

data class MfpNutrients(
    val carbohydrates: MfpAmount,
    val fat: MfpAmount,
    val protein: MfpAmount,
    val fiber: MfpAmount,
    val calories: MfpAmount,
) {
    operator fun get(nutrient: MfpNutrient): MfpAmount = when (nutrient) {
        MfpNutrient.CARBOHYDRATES -> carbohydrates
        MfpNutrient.FAT -> fat
        MfpNutrient.PROTEIN -> protein
        MfpNutrient.FIBER -> fiber
        MfpNutrient.CALORIES -> calories
    }

    companion object {
        fun of(amount: (MfpNutrient) -> MfpAmount): MfpNutrients = MfpNutrients(
            amount(MfpNutrient.CARBOHYDRATES), amount(MfpNutrient.FAT), amount(MfpNutrient.PROTEIN),
            amount(MfpNutrient.FIBER), amount(MfpNutrient.CALORIES))
    }
}
