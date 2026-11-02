package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.tools.Calculator
import io.github.ahmetdurmus1218.jarvis.core.tools.UnitConverter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolsTest {
    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-6, "$expected != $actual")

    @Test
    fun calculatorBasics() {
        near(14.0, Calculator.evaluate("(3+4)*2"))
        near(2.0 + 3.0 * 4.0, Calculator.evaluate("2 + 3 * 4"))
        near(512.0, Calculator.evaluate("2^3^2"))
        near(-4.0, Calculator.evaluate("-2*2"))
        near(1.0, Calculator.evaluate("10 % 3"))
        near(3.5, Calculator.evaluate("3,5"))
        near(1234.5, Calculator.evaluate("1.234,5"))
        near(4.0, Calculator.evaluate("sqrt(16)"))
        near(1.0, Calculator.evaluate("sin(90)"))
        near(42.0, Calculator.evaluate(Calculator.fromSpeech("12 artı 30 kaç eder")))
        near(84.0, Calculator.evaluate(Calculator.fromSpeech("12 çarpı 7")))
    }

    @Test
    fun calculatorRejectsAnythingThatIsNotArithmetic() {
        assertFailsWith<Calculator.CalcException> { Calculator.evaluate("1/0") }
        assertFailsWith<Calculator.CalcException> { Calculator.evaluate("System.exit(0)") }
        assertFailsWith<Calculator.CalcException> { Calculator.evaluate("2 +") }
        assertFailsWith<Calculator.CalcException> { Calculator.evaluate("(1+2") }
        assertFailsWith<Calculator.CalcException> { Calculator.evaluate("1".repeat(600)) }
        assertFailsWith<Calculator.CalcException> { Calculator.evaluate("(".repeat(100) + "1" + ")".repeat(100)) }
    }

    @Test
    fun formatsTurkishStyle() {
        assertEquals("42", Calculator.format(42.0))
        assertEquals("3,5", Calculator.format(3.5))
        assertEquals("0,3333333333", Calculator.format(1.0 / 3))
    }

    @Test
    fun unitConversion() {
        near(3.1068559612, UnitConverter.convert(5.0, "km", "mil").converted)
        near(37.7777777778, UnitConverter.convert(100.0, "fahrenhayt", "derece").converted)
        near(273.15, UnitConverter.convert(0.0, "c", "kelvin").converted)
        near(1024.0, UnitConverter.convert(1.0, "gb", "mb").converted)
        near(100.0, UnitConverter.convert(360.0, "km/sa", "m/sn").converted * 1.0 - 0.0)
        assertFailsWith<UnitConverter.ConversionException> { UnitConverter.convert(1.0, "kg", "metre") }
        assertEquals(Triple(5.0, "km", "mil"), UnitConverter.parse("5 km kaç mil"))
        assertEquals(Triple(3.5, "kg", "pound"), UnitConverter.parse("3,5 kg kaç pound eder"))
    }
}
