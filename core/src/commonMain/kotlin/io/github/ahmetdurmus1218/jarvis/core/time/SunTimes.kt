package io.github.ahmetdurmus1218.jarvis.core.time

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** A place for offline sun times (no location service, no network). */
data class Place(val name: String, val lat: Double, val lon: Double, val utcOffsetHours: Int = 3)

object Places {
    /** Large Turkish cities; Turkey is UTC+3 all year. */
    val TR = listOf(
        Place("İstanbul", 41.0082, 28.9784), Place("Ankara", 39.9334, 32.8597), Place("İzmir", 38.4237, 27.1428),
        Place("Manisa", 38.6191, 27.4289), Place("Bursa", 40.1826, 29.0665), Place("Antalya", 36.8969, 30.7133),
        Place("Adana", 37.0000, 35.3213), Place("Konya", 37.8746, 32.4932), Place("Gaziantep", 37.0662, 37.3833),
        Place("Kayseri", 38.7312, 35.4787), Place("Samsun", 41.2928, 36.3313), Place("Trabzon", 41.0027, 39.7168),
        Place("Diyarbakır", 37.9144, 40.2306), Place("Erzurum", 39.9043, 41.2679), Place("Eskişehir", 39.7767, 30.5206),
        Place("Denizli", 37.7765, 29.0864), Place("Van", 38.5012, 43.3730), Place("Edirne", 41.6771, 26.5557),
    )

    fun byName(name: String): Place = TR.firstOrNull { it.name == name } ?: TR.first()
}

/**
 * Sunrise and sunset by the NOAA solar position equations (accurate to about a minute), computed on
 * the device for a date and place. Times are minutes after local midnight; null in polar day/night.
 */
object SunTimes {
    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI

    /** Julian day at 0h UT of the Gregorian date. */
    private fun julianDay(y: Int, m: Int, d: Int): Double {
        var yy = y
        var mm = m
        if (mm <= 2) { yy -= 1; mm += 12 }
        val a = floor(yy / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (yy + 4716)) + floor(30.6001 * (mm + 1)) + d + b - 1524.5
    }

    data class Day(val sunriseMin: Int?, val sunsetMin: Int?, val noonMin: Int)

    fun compute(year: Int, month: Int, day: Int, place: Place): Day {
        val jd = julianDay(year, month, day) + 0.5 - place.utcOffsetHours / 24.0 // local noon-ish
        val t = (jd - 2451545.0) / 36525.0
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val mAnom = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(mAnom)) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(rad(2 * mAnom)) * (0.019993 - 0.000101 * t) + sin(rad(3 * mAnom)) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = deg(asin(sin(rad(eps)) * sin(rad(lambda))))
        val y = tan(rad(eps / 2)) * tan(rad(eps / 2))
        val eqTime = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(mAnom)) + 4 * e * y * sin(rad(mAnom)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(mAnom)),
        )
        val noon = 720 - 4 * place.lon - eqTime + place.utcOffsetHours * 60
        val cosH = (cos(rad(90.833)) / (cos(rad(place.lat)) * cos(rad(decl)))) - tan(rad(place.lat)) * tan(rad(decl))
        if (cosH > 1 || cosH < -1) return Day(null, null, noon.toInt())
        val ha = deg(acos(cosH))
        return Day((noon - 4 * ha).toInt(), (noon + 4 * ha).toInt(), noon.toInt())
    }

    fun hhmm(min: Int?): String = min?.let { "${(it / 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')}" } ?: "yok"
}
