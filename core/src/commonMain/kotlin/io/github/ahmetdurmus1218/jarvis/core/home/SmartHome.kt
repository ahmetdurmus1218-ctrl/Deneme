package io.github.ahmetdurmus1218.jarvis.core.home

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.core.text.TurkishNumbers

enum class Room(val label: String, val locative: String) { SALON("Salon", "salon"), STUDY("Çalışma odası", "çalışma odası") }
enum class Tone(val label: String) { WARM("sıcak"), NEUTRAL("nötr"), COLD("soğuk") }
enum class Foam(val label: String) { NONE("köpüksüz"), SOME("orta köpüklü"), LOTS("bol köpüklü") }
enum class Sugar(val label: String) { NONE("sade"), LITTLE("az şekerli"), MEDIUM("orta şekerli"), SWEET("şekerli") }
enum class Brew { IDLE, BREWING, READY }

data class Light(val room: Room, val on: Boolean = false, val brightness: Int = 80, val tone: Tone = Tone.WARM)

data class TurkishCoffee(
    val state: Brew = Brew.IDLE,
    val foam: Foam = Foam.SOME,
    val sugar: Sugar = Sugar.MEDIUM,
    val startedAt: Long = 0,
) {
    fun progress(now: Long) = if (state == Brew.BREWING) ((now - startedAt).toFloat() / SmartHome.TURKISH_MS).coerceIn(0f, 1f) else if (state == Brew.READY) 1f else 0f
}

data class FilterCoffee(
    val state: Brew = Brew.IDLE,
    val cups: Int = 2,
    val startedAt: Long = 0,
    /** After brewing the plate keeps the pot warm. */
    val keepWarm: Boolean = false,
) {
    val durationMs get() = cups * SmartHome.FILTER_CUP_MS
    fun progress(now: Long) = if (state == Brew.BREWING) ((now - startedAt).toFloat() / durationMs).coerceIn(0f, 1f) else if (state == Brew.READY) 1f else 0f
}

/** What a home command asks for (parsed from Turkish speech). */
sealed interface HomeAction {
    data class SetLight(val room: Room, val on: Boolean? = null, val brightness: Int? = null, val tone: Tone? = null) : HomeAction
    data class AllLights(val on: Boolean) : HomeAction
    data class MakeTurkishCoffee(val foam: Foam, val sugar: Sugar) : HomeAction
    data class MakeFilterCoffee(val cups: Int) : HomeAction
    data object CoffeeStatus : HomeAction
    data object HomeStatus : HomeAction
}

data class HomeState(
    val lights: List<Light> = Room.entries.map { Light(it) },
    val turkish: TurkishCoffee = TurkishCoffee(),
    val filter: FilterCoffee = FilterCoffee(),
) {
    fun light(r: Room) = lights.first { it.room == r }
}

/**
 * A simulated home (the devices live in the app; nothing on the network): two lights, a Turkish
 * coffee machine (~75 s) and a filter coffee machine (~40 s a cup). Shown as SİMÜLASYON on screen.
 */
class SmartHome(private var state: HomeState = HomeState()) {
    fun state(now: Long): HomeState {
        advance(now)
        return state
    }

    private fun advance(now: Long) {
        val t = state.turkish
        if (t.state == Brew.BREWING && now - t.startedAt >= TURKISH_MS) state = state.copy(turkish = t.copy(state = Brew.READY))
        val f = state.filter
        if (f.state == Brew.BREWING && now - f.startedAt >= f.durationMs) state = state.copy(filter = f.copy(state = Brew.READY, keepWarm = true))
    }

    /** Applies [a] and returns what Rick says about it. */
    fun apply(a: HomeAction, now: Long): String {
        advance(now)
        return when (a) {
            is HomeAction.SetLight -> {
                val old = state.light(a.room)
                val on = a.on ?: if (a.brightness != null || a.tone != null) true else old.on
                val new = old.copy(on = on, brightness = a.brightness ?: old.brightness, tone = a.tone ?: old.tone)
                state = state.copy(lights = state.lights.map { if (it.room == a.room) new else it })
                when {
                    !on -> "${a.room.label} ışığı kapatıldı."
                    a.brightness != null || a.tone != null ->
                        "${a.room.label} ışığı yüzde ${new.brightness}, ${new.tone.label} beyaz."
                    else -> "${a.room.label} ışığı açıldı."
                }
            }
            is HomeAction.AllLights -> {
                state = state.copy(lights = state.lights.map { it.copy(on = a.on) })
                if (a.on) "Tüm ışıklar açıldı." else "Tüm ışıklar kapatıldı."
            }
            is HomeAction.MakeTurkishCoffee -> {
                if (state.turkish.state == Brew.BREWING) {
                    "Türk kahveniz zaten pişiyor, ${remaining(TURKISH_MS - (now - state.turkish.startedAt))} kaldı."
                } else {
                    state = state.copy(turkish = TurkishCoffee(Brew.BREWING, a.foam, a.sugar, now))
                    "Hemen efendim. ${a.foam.label.replaceFirstChar { it.uppercase() }}, ${a.sugar.label} bir Türk kahvesi pişiyor; yaklaşık ${remaining(TURKISH_MS)} sürecek."
                }
            }
            is HomeAction.MakeFilterCoffee -> {
                if (state.filter.state == Brew.BREWING) {
                    "Filtre kahve zaten demleniyor."
                } else {
                    val f = FilterCoffee(Brew.BREWING, a.cups.coerceIn(1, 4), now)
                    state = state.copy(filter = f)
                    "${f.cups} fincan filtre kahve demleniyor; yaklaşık ${remaining(f.durationMs)} sürecek."
                }
            }
            HomeAction.CoffeeStatus -> coffeeStatus(now)
            HomeAction.HomeStatus -> {
                val lights = state.lights.joinToString(", ") { "${it.room.locative} ${if (it.on) "açık" else "kapalı"}" }
                "Işıklar: $lights. ${coffeeStatus(now)}"
            }
        }
    }

    /** Tapping a device on the Ev panel. */
    fun set(transform: (HomeState) -> HomeState) { state = transform(state) }

    private fun coffeeStatus(now: Long): String {
        val t = state.turkish
        val f = state.filter
        val parts = buildList {
            when (t.state) {
                Brew.BREWING -> add("Türk kahveniz ${remaining(TURKISH_MS - (now - t.startedAt))} içinde hazır")
                Brew.READY -> add("Türk kahveniz hazır, ${t.foam.label}")
                Brew.IDLE -> {}
            }
            when (f.state) {
                Brew.BREWING -> add("filtre kahve ${remaining(f.durationMs - (now - f.startedAt))} içinde hazır")
                Brew.READY -> add("filtre kahve hazır ve sıcak tutuluyor")
                Brew.IDLE -> {}
            }
        }
        return if (parts.isEmpty()) "Şu an hazırlanan bir kahve yok." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    companion object {
        const val TURKISH_MS = 75_000L
        const val FILTER_CUP_MS = 40_000L

        fun remaining(ms: Long): String {
            val s = ((ms + 999) / 1000).coerceAtLeast(1)
            return if (s >= 60) "${s / 60} dakika" + (if (s % 60 >= 10) " ${s % 60} saniye" else "") else "$s saniye"
        }
    }
}

/** Turkish home commands → [HomeAction]; null when the text is not one. */
object HomeCommands {
    private val LIGHT = Regex("\\b(?:isik|isig|lamba|aydinlatma)\\w*")
    private val ON = Regex("\\b(?:ac|yak)\\w*")
    private val OFF = Regex("\\b(?:kapa|kapat|sondur)\\w*")
    private val ALL = Regex("\\b(?:tum|butun|hepsi|her yer)\\w*")
    private val PERCENT = Regex("(?:yuzde|%)\\s*(\\d{1,3})")
    private val COFFEE = Regex("\\bkahve\\w*")
    private val TURKISH = Regex("\\bturk\\w*")
    private val FILTER = Regex("\\bfiltre\\w*")
    private val MAKE = Regex("\\b(?:yap|pisir|demle|hazirla|koy)\\w*")
    private val READY = Regex("\\b(?:hazir mi|hazir mı|ne durumda|oldu mu|bitti mi|ne zaman)")
    private val CUPS = Regex("(\\d)\\s*fincan")

    fun parse(text: String): HomeAction? {
        val n = TurkishNumbers.wordsToDigits(Turkish.normalize(text))
        val room = when {
            "salon" in n -> Room.SALON
            "calisma" in n || "ofis" in n -> Room.STUDY
            else -> null
        }
        val tone = when {
            "soguk" in n -> Tone.COLD
            "notr" in n || "dogal" in n -> Tone.NEUTRAL
            "sicak" in n -> Tone.WARM
            else -> null
        }
        val pct = PERCENT.find(n)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100)
        if (COFFEE.containsMatchIn(n)) {
            if (READY.containsMatchIn(n)) return HomeAction.CoffeeStatus
            if (MAKE.containsMatchIn(n)) {
                if (FILTER.containsMatchIn(n)) return HomeAction.MakeFilterCoffee(CUPS.find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 2)
                if (TURKISH.containsMatchIn(n) || "kopuk" in n || "seker" in n || "sade" in n) {
                    val foam = when {
                        "bol kopuk" in n -> Foam.LOTS
                        "kopuksuz" in n -> Foam.NONE
                        else -> Foam.SOME
                    }
                    val sugar = when {
                        "sade" in n || "sekersiz" in n -> Sugar.NONE
                        "az sekerli" in n -> Sugar.LITTLE
                        "orta" in n -> Sugar.MEDIUM
                        "sekerli" in n -> Sugar.SWEET
                        else -> Sugar.MEDIUM
                    }
                    return HomeAction.MakeTurkishCoffee(foam, sugar)
                }
            }
            return null
        }
        if (LIGHT.containsMatchIn(n) || room != null && (pct != null || tone != null)) {
            val on = when {
                OFF.containsMatchIn(n) -> false
                ON.containsMatchIn(n) -> true
                else -> null
            }
            if (ALL.containsMatchIn(n) && on != null) return HomeAction.AllLights(on)
            val r = room ?: return null
            if (on == null && pct == null && tone == null) return null
            return HomeAction.SetLight(r, on, pct, tone)
        }
        if (Regex("\\bev\\w* (?:durum|nasil)").containsMatchIn(n)) return HomeAction.HomeStatus
        return null
    }
}
