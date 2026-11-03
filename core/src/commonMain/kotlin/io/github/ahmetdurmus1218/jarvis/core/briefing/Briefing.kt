package io.github.ahmetdurmus1218.jarvis.core.briefing

import io.github.ahmetdurmus1218.jarvis.core.home.Brew
import io.github.ahmetdurmus1218.jarvis.core.home.HomeState
import io.github.ahmetdurmus1218.jarvis.core.library.Book
import io.github.ahmetdurmus1218.jarvis.core.scene.Gorsel
import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import io.github.ahmetdurmus1218.jarvis.core.scene.Sahne
import io.github.ahmetdurmus1218.jarvis.core.stt.WakePhrase
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.core.time.Now
import io.github.ahmetdurmus1218.jarvis.core.time.Place
import io.github.ahmetdurmus1218.jarvis.core.time.SunTimes
import io.github.ahmetdurmus1218.jarvis.core.todo.Todo

/**
 * The offline daily briefing ("Jarvis, brifing ver" / "aydınlat beni"): instead of news from the
 * internet, a scene-by-scene summary of the user's own day — date, daylight, open to-dos, the book
 * in progress, the home, what Jarvis remembers. Every number comes from the device.
 */
object Briefing {
    private val TRIGGER = Regex("^(?:hey )?(?:jarvis|cervis)?[ ,]*(?:brifing(?: ver)?|bana brifing ver|aydinlat beni|gunun ozeti|gunumu ozetle|bugun ne var|sabah ozeti|gunluk ozet)$")

    fun matches(text: String): Boolean = TRIGGER.matches(Turkish.normalize(text).replace(Regex("[?.!]+$"), "").trim())

    data class Input(
        val now: Now,
        val place: Place,
        val todos: List<Todo>,
        val reading: List<Book>,
        val home: HomeState?,
        val facts: Int,
        val userName: String = "",
    )

    fun scenes(i: Input): List<Sahne> {
        val n = i.now
        val date = "${n.day} ${Turkish.monthName(n.month)} ${n.year}"
        val hello = WakePhrase.greeting(n.hour).removeSuffix(" efendim.").let { g ->
            if (i.userName.isNotBlank()) "$g ${i.userName}." else "$g efendim."
        }
        val out = mutableListOf(
            Sahne("$hello Bugün $date, ${Turkish.dayName(n.isoDayOfWeek)}. Saat ${n.hhmm()}.", Gorsel(GorselTip.BASLIK, baslik = "$date · ${Turkish.dayName(n.isoDayOfWeek)}")),
        )
        val sun = SunTimes.compute(n.year, n.month, n.day, i.place)
        if (sun.sunriseMin != null && sun.sunsetMin != null) {
            val nowMin = n.hour * 60 + n.minute
            val left = sun.sunsetMin - nowMin
            val ses = if (left > 0 && nowMin >= sun.sunriseMin) {
                "${i.place.name} için gün batımı saat ${SunTimes.hhmm(sun.sunsetMin)}; ${left / 60} saat ${left % 60} dakika gün ışığı kaldı."
            } else {
                "${i.place.name} için gün doğumu saat ${SunTimes.hhmm(sun.sunriseMin)}, gün batımı ${SunTimes.hhmm(sun.sunsetMin)}."
            }
            out += Sahne(ses, Gorsel(GorselTip.SAYI, deger = SunTimes.hhmm(sun.sunsetMin), etiket = "Gün batımı · ${i.place.name}"))
        }
        val open = i.todos.filter { !it.done }
        out += if (open.isEmpty()) {
            Sahne("Yapılacaklar listeniz boş.")
        } else {
            Sahne("Listenizde ${open.size} iş var.", Gorsel(GorselTip.LISTE, baslik = "Yapılacaklar", ogeler = open.take(5).map { it.text }))
        }
        i.reading.firstOrNull()?.let { b ->
            out += Sahne("Okumakta olduğunuz kitap ${b.title}" + (b.page?.let { "; $it. sayfadasınız." } ?: "."), Gorsel(GorselTip.BASLIK, baslik = "📚 ${b.title}"))
        }
        i.home?.let { h ->
            val lights = h.lights.count { it.on }
            val coffee = when {
                h.turkish.state == Brew.READY -> " Türk kahveniz hazır."
                h.turkish.state == Brew.BREWING -> " Türk kahveniz pişiyor."
                h.filter.state == Brew.READY -> " Filtre kahve hazır."
                else -> ""
            }
            out += Sahne((if (lights == 0) "Evde ışıklar kapalı." else "Evde $lights ışık açık.") + coffee)
        }
        if (i.facts > 0) out += Sahne("Hafızamda sizinle ilgili ${i.facts} bilgi var.")
        out += Sahne("Hepsi bu kadar. İyi bir gün dilerim.")
        return out
    }
}
