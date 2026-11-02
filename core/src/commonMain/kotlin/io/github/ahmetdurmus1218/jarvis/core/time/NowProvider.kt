package io.github.ahmetdurmus1218.jarvis.core.time

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Wall-clock snapshot in the device time zone. */
data class Now(
    val year: Int,
    /** 1..12 */
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    /** 1 = Monday .. 7 = Sunday */
    val isoDayOfWeek: Int,
    val zone: String,
    val epochMillis: Long,
) {
    fun hhmm(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

fun interface NowProvider {
    fun now(): Now

    companion object {
        @OptIn(ExperimentalTime::class)
        val System: NowProvider = NowProvider {
            val instant = Clock.System.now()
            val tz = TimeZone.currentSystemDefault()
            val t = instant.toLocalDateTime(tz)
            Now(
                year = t.year,
                month = t.month.ordinal + 1,
                day = t.day,
                hour = t.hour,
                minute = t.minute,
                isoDayOfWeek = t.dayOfWeek.ordinal + 1,
                zone = tz.id,
                epochMillis = instant.toEpochMilliseconds(),
            )
        }
    }
}
