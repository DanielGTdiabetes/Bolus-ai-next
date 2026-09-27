package org.bolusai.profile

/**
 * Non-negative decimal in canonical storage form (ADR 0012, section 4.2).
 *
 * At most 6 integer and 3 fractional digits: a representation and storage bound, never a clinical range.
 * `"0"` is a valid value and is distinct from a value that is not configured.
 */
data class CanonicalDecimal(val text: String) {
    init { require(PATTERN.matches(text)) { "profile.decimal.not_canonical" } }

    override fun toString(): String = text

    companion object {
        private val PATTERN = Regex("^(0|[1-9][0-9]{0,5})(\\.[0-9]{0,2}[1-9])?$")
        const val MAX_INTEGER_DIGITS = 6
        const val MAX_FRACTION_DIGITS = 3

        fun isCanonical(text: String): Boolean = PATTERN.matches(text)
    }
}

/** Result of reading one user-typed value. Blank input is "not configured", never zero. */
sealed interface DecimalInput {
    data object Blank : DecimalInput
    data class Valid(val value: CanonicalDecimal) : DecimalInput
    data class Invalid(val reason: ProfileFailure) : DecimalInput
}

object DecimalInputs {
    /**
     * Accepts one ',' or '.' decimal separator and ASCII digits only. Rejects signs, exponents, grouping and
     * anything that could be read as thousands in Spanish notation (three decimals with a non-zero integer part).
     */
    fun parse(input: String): DecimalInput {
        val text = input.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        if (text.isEmpty()) return DecimalInput.Blank
        if (text.any { it != ',' && it != '.' && it !in '0'..'9' }) {
            return DecimalInput.Invalid(
                if (text.any { it == ' ' || it == ' ' || it == '\'' }) ProfileFailure.AMBIGUOUS_DECIMAL
                else ProfileFailure.INVALID_VALUE)
        }
        val separators = text.count { it == ',' || it == '.' }
        if (separators > 1) return DecimalInput.Invalid(ProfileFailure.AMBIGUOUS_DECIMAL)
        val separator = text.indexOfFirst { it == ',' || it == '.' }
        val integer = if (separator < 0) text else text.substring(0, separator)
        val fraction = if (separator < 0) "" else text.substring(separator + 1)
        if (integer.isEmpty() || (separator >= 0 && fraction.isEmpty())) {
            return DecimalInput.Invalid(ProfileFailure.INVALID_VALUE)
        }
        val canonicalInteger = integer.trimStart('0').ifEmpty { "0" }
        if (fraction.length == 3 && canonicalInteger != "0") return DecimalInput.Invalid(ProfileFailure.AMBIGUOUS_DECIMAL)
        if (canonicalInteger.length > CanonicalDecimal.MAX_INTEGER_DIGITS ||
            fraction.length > CanonicalDecimal.MAX_FRACTION_DIGITS) {
            return DecimalInput.Invalid(ProfileFailure.VALUE_NOT_REPRESENTABLE)
        }
        val canonicalFraction = fraction.trimEnd('0')
        val canonical = if (canonicalFraction.isEmpty()) canonicalInteger else "$canonicalInteger.$canonicalFraction"
        return DecimalInput.Valid(CanonicalDecimal(canonical))
    }
}
