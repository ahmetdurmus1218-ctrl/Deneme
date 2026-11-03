package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.time.Places
import io.github.ahmetdurmus1218.jarvis.core.time.SunTimes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SunTimesTest {
    private fun near(expected: String, actual: Int?, tol: Int = 4) {
        val (h, m) = expected.split(':').map { it.toInt() }
        assertTrue(actual != null && abs(actual - (h * 60 + m)) <= tol, "expected $expected, got ${SunTimes.hhmm(actual)}")
    }

    @Test
    fun knownSunTimes() {
        // Istanbul: published almanac (UTC+3). Ankara: solar noon 12:46 (45°E meridian, 32.86°E, EoT +2 min) ± half of a 9 h 18 min day.
        val ist = SunTimes.compute(2026, 6, 21, Places.byName("İstanbul"))
        near("05:32", ist.sunriseMin); near("20:39", ist.sunsetMin)
        val ank = SunTimes.compute(2026, 12, 21, Places.byName("Ankara"))
        near("08:07", ank.sunriseMin); near("17:26", ank.sunsetMin)
    }
}
