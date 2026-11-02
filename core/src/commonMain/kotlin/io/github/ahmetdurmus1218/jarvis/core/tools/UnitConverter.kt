package io.github.ahmetdurmus1218.jarvis.core.tools

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.core.text.TurkishNumbers

/** The `birim_cevir` tool: length, mass, volume, area, speed, time, data and temperature. */
object UnitConverter {

    enum class Dimension { LENGTH, MASS, VOLUME, AREA, SPEED, TIME, DATA, TEMPERATURE }

    data class Unit(val id: String, val dimension: Dimension, val factor: Double, val names: List<String>)

    data class Result(val value: Double, val from: Unit, val to: Unit, val converted: Double)

    class ConversionException(message: String) : Exception(message)

    private val UNITS = listOf(
        Unit("mm", Dimension.LENGTH, 0.001, listOf("mm", "milimetre")),
        Unit("cm", Dimension.LENGTH, 0.01, listOf("cm", "santimetre", "santim")),
        Unit("m", Dimension.LENGTH, 1.0, listOf("m", "metre")),
        Unit("km", Dimension.LENGTH, 1000.0, listOf("km", "kilometre")),
        Unit("in", Dimension.LENGTH, 0.0254, listOf("inc", "inch", "in")),
        Unit("ft", Dimension.LENGTH, 0.3048, listOf("ft", "feet", "fit", "ayak")),
        Unit("yd", Dimension.LENGTH, 0.9144, listOf("yard", "yd")),
        Unit("mi", Dimension.LENGTH, 1609.344, listOf("mil", "mile", "mi")),
        Unit("nmi", Dimension.LENGTH, 1852.0, listOf("deniz mili")),
        Unit("mg", Dimension.MASS, 1e-6, listOf("mg", "miligram")),
        Unit("g", Dimension.MASS, 0.001, listOf("g", "gr", "gram")),
        Unit("kg", Dimension.MASS, 1.0, listOf("kg", "kilogram", "kilo")),
        Unit("t", Dimension.MASS, 1000.0, listOf("ton")),
        Unit("lb", Dimension.MASS, 0.45359237, listOf("lb", "pound", "libre")),
        Unit("oz", Dimension.MASS, 0.028349523125, listOf("oz", "ons")),
        Unit("ml", Dimension.VOLUME, 0.001, listOf("ml", "mililitre")),
        Unit("l", Dimension.VOLUME, 1.0, listOf("l", "lt", "litre")),
        Unit("m3", Dimension.VOLUME, 1000.0, listOf("m3", "metrekup")),
        Unit("gal", Dimension.VOLUME, 3.785411784, listOf("galon", "gallon", "gal")),
        Unit("m2", Dimension.AREA, 1.0, listOf("m2", "metrekare")),
        Unit("km2", Dimension.AREA, 1e6, listOf("km2", "kilometrekare")),
        Unit("ha", Dimension.AREA, 1e4, listOf("hektar", "ha")),
        Unit("donum", Dimension.AREA, 1000.0, listOf("donum", "dekar")),
        Unit("acre", Dimension.AREA, 4046.8564224, listOf("akre", "acre")),
        Unit("kmh", Dimension.SPEED, 1 / 3.6, listOf("km/s", "km/sa", "kmh", "km/h", "kilometre/saat")),
        Unit("ms", Dimension.SPEED, 1.0, listOf("m/sn", "m/s", "metre/saniye")),
        Unit("mph", Dimension.SPEED, 0.44704, listOf("mph", "mil/saat")),
        Unit("knot", Dimension.SPEED, 1852.0 / 3600.0, listOf("knot", "deniz mili/saat", "kusat")),
        Unit("s", Dimension.TIME, 1.0, listOf("sn", "saniye")),
        Unit("min", Dimension.TIME, 60.0, listOf("dk", "dakika")),
        Unit("h", Dimension.TIME, 3600.0, listOf("saat", "sa")),
        Unit("day", Dimension.TIME, 86400.0, listOf("gun")),
        Unit("week", Dimension.TIME, 604800.0, listOf("hafta")),
        Unit("B", Dimension.DATA, 1.0, listOf("bayt", "byte", "b")),
        Unit("KB", Dimension.DATA, 1024.0, listOf("kb", "kilobayt", "kilobyte")),
        Unit("MB", Dimension.DATA, 1024.0 * 1024, listOf("mb", "megabayt", "megabyte")),
        Unit("GB", Dimension.DATA, 1024.0 * 1024 * 1024, listOf("gb", "gigabayt", "gigabyte")),
        Unit("TB", Dimension.DATA, 1024.0 * 1024 * 1024 * 1024, listOf("tb", "terabayt", "terabyte")),
        Unit("C", Dimension.TEMPERATURE, 1.0, listOf("c", "santigrat", "derece", "celsius", "°c")),
        Unit("F", Dimension.TEMPERATURE, 1.0, listOf("f", "fahrenhayt", "fahrenheit", "°f")),
        Unit("K", Dimension.TEMPERATURE, 1.0, listOf("k", "kelvin")),
    )

    fun find(name: String): Unit? {
        val n = Turkish.normalize(name)
        // Accept Turkish case/copula suffixes: "mile", "metreye", "metredir", "dereceye".
        val candidates = listOf(n) + listOf("dir", "dur", "tir", "tur", "ye", "ya", "e", "a")
            .filter { n.endsWith(it) && n.length > it.length + 1 }
            .map { n.removeSuffix(it) }
        for (c in candidates) {
            UNITS.firstOrNull { u -> c in u.names }?.let { return it }
        }
        return null
    }

    fun convert(value: Double, fromName: String, toName: String): Result {
        val from = find(fromName) ?: throw ConversionException("Bilinmeyen birim: $fromName")
        val to = find(toName) ?: throw ConversionException("Bilinmeyen birim: $toName")
        if (from.dimension != to.dimension) {
            throw ConversionException("${from.id} ile ${to.id} birbirine çevrilemez")
        }
        val converted = if (from.dimension == Dimension.TEMPERATURE) {
            val celsius = when (from.id) {
                "C" -> value
                "F" -> (value - 32) * 5 / 9
                else -> value - 273.15
            }
            when (to.id) {
                "C" -> celsius
                "F" -> celsius * 9 / 5 + 32
                else -> celsius + 273.15
            }
        } else {
            value * from.factor / to.factor
        }
        return Result(value, from, to, converted)
    }

    /** Parses phrases like "5 km kaç mil", "100 fahrenhayt kaç derece", "3,5 kg kaç pound". */
    fun parse(text: String): Triple<Double, String, String>? {
        val n = TurkishNumbers.wordsToDigits(Turkish.normalize(text))
        val m = Regex("^(-?[\\d.,]+)\\s*([a-z°/0-9 ]+?)\\s+(?:kac|ne kadar|kac tane)\\s+([a-z°/0-9 ]+?)(?:\\s+(?:eder|yapar|olur|dir|dir mi))?$")
            .find(n) ?: Regex("^(-?[\\d.,]+)\\s*([a-z°/0-9 ]+?)\\s+(?:to|->|=)\\s*([a-z°/0-9 ]+)$").find(n) ?: return null
        val value = Calculator.parseLocalizedNumber(m.groupValues[1].removePrefix("-"))?.let {
            if (m.groupValues[1].startsWith("-")) -it else it
        } ?: return null
        val from = m.groupValues[2].trim()
        val to = m.groupValues[3].trim()
        if (find(from) == null || find(to) == null) return null
        return Triple(value, from, to)
    }
}
