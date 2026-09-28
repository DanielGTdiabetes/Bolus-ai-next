package org.bolusai.profile

import kotlin.test.*

class ProfileDecimalTest {
    private fun valid(input: String) = (DecimalInputs.parse(input) as DecimalInput.Valid).value.text
    private fun invalid(input: String) = (DecimalInputs.parse(input) as DecimalInput.Invalid).reason

    @Test fun blankIsNotConfiguredAndZeroIsAValue() {
        assertEquals(DecimalInput.Blank, DecimalInputs.parse(""))
        assertEquals(DecimalInput.Blank, DecimalInputs.parse("   "))
        assertEquals("0", valid("0"))
        assertEquals("0", valid("0,0"))
        assertEquals("0", valid("000"))
        assertNotEquals(DecimalInputs.parse(""), DecimalInputs.parse("0"))
    }

    @Test fun commaAndPointAreCanonicalised() {
        assertEquals("12.5", valid("12,5"))
        assertEquals("12.5", valid("12.50"))
        assertEquals("7.5", valid("007,50"))
        assertEquals("0.125", valid("0,125"))
        assertEquals("999999.99", valid("999999,99"))
        assertEquals("15", valid(" 15 "))
    }

    @Test fun groupingThatCouldMeanThousandsIsRejected() {
        listOf("1.000", "2,125", "1.000,5", "1,000.5", "1 000", "1'000", "1 000").forEach {
            assertEquals(ProfileFailure.AMBIGUOUS_DECIMAL, invalid(it), it)
        }
    }

    @Test fun nonDecimalInputIsRejectedWithoutCoercion() {
        listOf("-1", "+1", "1e3", "NaN", "Infinity", "abc", ".5", "5.", ",", "١٢", "0x10").forEach {
            assertEquals(ProfileFailure.INVALID_VALUE, invalid(it), it)
        }
    }

    @Test fun representationBoundIsTechnicalNotClinical() {
        assertEquals(ProfileFailure.VALUE_NOT_REPRESENTABLE, invalid("1000000"))
        assertEquals(ProfileFailure.VALUE_NOT_REPRESENTABLE, invalid("0,1234"))
        assertEquals(ProfileFailure.VALUE_NOT_REPRESENTABLE, invalid("12,3456"))
        assertEquals("999999", valid("999999"))
    }

    @Test fun canonicalConstructorRejectsEverySpellingButOne() {
        listOf("12.5", "0", "0.001", "999999.999").forEach { assertTrue(CanonicalDecimal.isCanonical(it), it) }
        listOf("12.50", "012", "-1", "1,5", "", "1.", "1.2345", "1000000").forEach {
            assertFalse(CanonicalDecimal.isCanonical(it), it)
            assertFailsWith<IllegalArgumentException> { CanonicalDecimal(it) }
        }
    }
}
