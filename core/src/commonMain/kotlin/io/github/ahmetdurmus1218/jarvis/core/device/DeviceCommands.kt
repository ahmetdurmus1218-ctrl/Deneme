package io.github.ahmetdurmus1218.jarvis.core.device

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish

/**
 * Things the user asks the device itself to do, answered at once without the language model:
 * the torch, volume, music, system setting panels, the battery, preparing a call / a message,
 * directions. The platform carries them out with plain system calls and Intents. Calls and
 * messages are only PREPARED (dialer / composer opens with the number and text); the user presses
 * call or send there, Rick has no permission to do either by itself.
 */
sealed interface DeviceAction {
    data class Torch(val on: Boolean) : DeviceAction
    data class Volume(val change: VolumeChange) : DeviceAction
    data class Media(val key: MediaKey) : DeviceAction
    data class Panel(val panel: SettingsPanel, val wantOn: Boolean?) : DeviceAction
    data object Battery : DeviceAction
    /** [target]: digits of a number, or a contact name as said ("annem"). */
    data class Call(val target: String) : DeviceAction
    /** [target] may be null ("mesaj yaz: geliyorum" — the composer asks whom to). */
    data class Message(val target: String?, val text: String) : DeviceAction
    data class Navigate(val place: String) : DeviceAction
}

enum class VolumeChange { UP, DOWN, MUTE, MAX, SILENT_PHONE }
enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }

enum class SettingsPanel(val label: String) {
    WIFI("Wi-Fi"), BLUETOOTH("Bluetooth"), INTERNET("Mobil veri"), LOCATION("Konum"), AIRPLANE("Uçak modu"),
    DISPLAY("Ekran parlaklığı"), BATTERY_SAVER("Pil tasarrufu"), DND("Rahatsız etmeyin"), SOUND("Ses ayarları"),
}

object DeviceCommands {
    private const val ON = "(?:ac|yak|calistir|etkinlestir)"
    private const val OFF = "(?:kapat|kapa|sondur|devre disi birak)"

    private val TORCH = Regex("^(?:el )?(?:fener(?:i|ini)?|fener isigi(?:ni)?|flas(?:i|i)?)\\s+($ON|$OFF)\\w*$")
    private val VOLUME = Regex("^(?:telefonun |bilgisayarin |muzigin |medya )?(?:ses|sesi|sesini)\\s+(?:biraz |cok |bir tik )?(ac|artir|yukselt|kis|azalt|dusur|kapat|kapa|sifirla|en yuksege cikar|en yuksek yap|fulle|sonuna kadar ac)\\w*$")
    private val SILENT = Regex("^(?:telefonu |zil sesini |zili )(?:sessize al|sessize gec|titresime al|sustur)$")
    private val PLAY = Regex("^(?:muzi\\w*|sarki\\w*|sarkiyi|muzigi)\\s+(?:baslat|cal|oynat|devam ettir|surdur)\\w*$|^(?:muzik|sarki) cal$")
    private val PAUSE = Regex("^(?:muzi\\w*|sarki\\w*)\\s+(?:durdur|duraklat|dur|kes)\\w*$")
    private val NEXT = Regex("^(?:sonraki|siradaki|baska) sarki\\w*$|^sarkiyi (?:gec|degistir|atla)\\w*$|^(?:sonraki|ileri)$")
    private val PREVIOUS = Regex("^(?:onceki|bir onceki) sarki\\w*$|^(?:geri al sarkiyi|onceki)$")
    private val PANEL = Regex("^(wi ?fi|wifi|kablosuz|bluetooth|mobil veri|internet|konum|gps|ucak modu|parlaklik|parlaklig|ekran parlakligi|pil tasarrufu|guc tasarrufu|rahatsiz etme\\w*|ses ayarlari)(?:i|ni|yi|u|nu|yu)?\\s+(ac|kapat|kapa|ayar\\w*|goster|duzenle)\\w*$")
    private val BATTERY = Regex("^(?:pil|sarj|batarya)(?:im|in|imiz)?(?: durumu| seviyesi| yuzdesi)?(?: kac| ne kadar| ne durumda| yuzde kac| kac kaldi| bitti mi| ne)?$|^kac sarjim (?:var|kaldi)$|^(?:pil|sarj) yuzde kac$")
    private val CALL_NUMBER = Regex("^([+0-9][0-9 ]{1,16}[0-9])(?:'?(?:i|u|yi|yu|ı))?\\s+(?:numarasini\\s+)?(?:ara|arar misin|cevir)$")
    private val CALL_NAME = Regex("^(.{2,30}?)\\s+(?:ara|arar misin|arasana|telefon et|telefonla)$")
    private val MESSAGE = Regex("^(?:(.{2,30}?)'?(?:e|a|ye|ya|ne|na)\\s+)?(?:mesaj|sms)\\s+(?:at|gonder|yaz|yolla)\\w*[: ,]+(.+)$")
    private val NAVIGATE = Regex("^(.{2,40}?)'?(?:e|a|ye|ya|ne|na)?\\s+(?:yol tarifi(?: ver)?|nasil gidilir|nasil giderim|navigasyon(?: ac)?|navigasyonu ac)$|^(?:haritada|haritadan)\\s+(.{2,40})\\s+(?:goster|ac|bul)$")

    /** [raw] keeps the user's own spelling for names, numbers and message text. */
    fun parse(raw: String, normalized: String = Turkish.normalize(raw).replace(Regex("[?.!]+$"), "").trim()): DeviceAction? {
        val n = normalized
        TORCH.matchEntire(n)?.let { return DeviceAction.Torch(on = Regex("^$ON").containsMatchIn(it.groupValues[1])) }
        if (SILENT.matches(n)) return DeviceAction.Volume(VolumeChange.SILENT_PHONE)
        VOLUME.matchEntire(n)?.let { m ->
            val w = m.groupValues[1]
            val change = when {
                w.startsWith("en yuksek") || w == "fulle" || w.startsWith("sonuna") -> VolumeChange.MAX
                w in setOf("ac", "artir", "yukselt") -> VolumeChange.UP
                w in setOf("kapat", "kapa", "sifirla") -> VolumeChange.MUTE
                else -> VolumeChange.DOWN
            }
            return DeviceAction.Volume(change)
        }
        if (PAUSE.matches(n)) return DeviceAction.Media(MediaKey.PAUSE)
        if (PLAY.matches(n)) return DeviceAction.Media(MediaKey.PLAY)
        if (NEXT.matches(n)) return DeviceAction.Media(MediaKey.NEXT)
        if (PREVIOUS.matches(n)) return DeviceAction.Media(MediaKey.PREVIOUS)
        PANEL.matchEntire(n)?.let { m ->
            val p = when (val w = m.groupValues[1]) {
                "bluetooth" -> SettingsPanel.BLUETOOTH
                "mobil veri", "internet" -> SettingsPanel.INTERNET
                "konum", "gps" -> SettingsPanel.LOCATION
                "ucak modu" -> SettingsPanel.AIRPLANE
                "parlaklik", "parlaklig", "ekran parlakligi" -> SettingsPanel.DISPLAY
                "pil tasarrufu", "guc tasarrufu" -> SettingsPanel.BATTERY_SAVER
                "ses ayarlari" -> SettingsPanel.SOUND
                else -> if (w.startsWith("rahatsiz")) SettingsPanel.DND else SettingsPanel.WIFI
            }
            val verb = m.groupValues[2]
            return DeviceAction.Panel(p, if (verb == "ac") true else if (verb.startsWith("kapa")) false else null)
        }
        if (BATTERY.matches(n)) return DeviceAction.Battery
        CALL_NUMBER.matchEntire(n)?.let { return DeviceAction.Call(it.groupValues[1].filter { c -> c.isDigit() || c == '+' }) }
        MESSAGE.matchEntire(n)?.let { m ->
            val text = original(raw, m.groupValues[2]).trim()
            val who = m.groupValues[1].takeIf { it.isNotBlank() }?.let { original(raw, it).trim().trimEnd('\'', '’') }
            if (text.isNotEmpty()) return DeviceAction.Message(who, text)
        }
        CALL_NAME.matchEntire(n)?.let { m ->
            val who = m.groupValues[1]
            // "beni ara", "Wikipedia'da kedi ara", "belgelerimde vergi ara" are not calls.
            if (looksLikePerson(who)) {
                return DeviceAction.Call(contactName(original(raw, who)))
            }
        }
        NAVIGATE.matchEntire(n)?.let { m ->
            val place = m.groupValues[1].takeIf { it.isNotBlank() }?.let { original(raw, it).trim().trimEnd('\'', '’') }
                ?: contactName(original(raw, m.groupValues[2])) // "Kızılay'ı" → "Kızılay"
            if (place.isNotBlank()) return DeviceAction.Navigate(place)
        }
        return null
    }

    private val NOT_PEOPLE = setOf("beni", "bunu", "sunu", "onu", "bunlari", "internette", "googleda", "wikipediada", "kutuphanede", "kitaplikta", "hafizada", "belgelerde", "belgelerimde", "dosyalarda")

    private fun looksLikePerson(who: String): Boolean {
        val words = who.split(' ')
        if (words.size > 2 || words.first() in NOT_PEOPLE || " ve " in who) return false
        // A place in the sentence ("Ankara'da otel ara") means searching, not calling: a person to call
        // carries the object ending ("Ahmet'i", "annemi") instead.
        return words.none { (it.length > 3 && Regex("(?:da|de|ta|te)$").containsMatchIn(it)) || it in NOT_PEOPLE }
    }

    /** "Annemi" → "Annem", "Ahmet'i" → "Ahmet": the accusative ending of a contact name. */
    fun contactName(said: String): String {
        val s = said.trim()
        val apo = s.indexOfAny(charArrayOf('\'', '’'))
        if (apo > 0) return s.substring(0, apo)
        val lower = Turkish.normalize(s)
        val vowels = "aeiou"
        return when {
            lower.length > 4 && (lower.endsWith("yi") || lower.endsWith("yu")) && lower[lower.length - 3] in vowels -> s.dropLast(2)
            lower.length > 3 && lower.last() in "iu" && lower[lower.length - 2] !in vowels -> s.dropLast(1)
            else -> s
        }
    }

    private fun original(raw: String, normalizedPart: String): String = Turkish.cutOriginal(raw, normalizedPart)

    /** What Rick says while doing it. */
    fun describe(a: DeviceAction): String = when (a) {
        is DeviceAction.Torch -> if (a.on) "Fener açıldı." else "Fener kapatıldı."
        is DeviceAction.Volume -> when (a.change) {
            VolumeChange.UP -> "Sesi açtım."
            VolumeChange.DOWN -> "Sesi kıstım."
            VolumeChange.MUTE -> "Sesi kapattım."
            VolumeChange.MAX -> "Ses en yüksekte."
            VolumeChange.SILENT_PHONE -> "Telefon titreşime alındı."
        }
        is DeviceAction.Media -> when (a.key) {
            MediaKey.PLAY -> "Müzik çalıyor."
            MediaKey.PAUSE -> "Müzik durduruldu."
            MediaKey.NEXT -> "Sonraki şarkı."
            MediaKey.PREVIOUS -> "Önceki şarkı."
        }
        is DeviceAction.Panel -> "${a.panel.label} ayarı açılıyor" + when (a.wantOn) {
            true -> "; açmak için tek dokunuş yeter."
            false -> "; kapatmak için tek dokunuş yeter."
            null -> "."
        }
        DeviceAction.Battery -> "Pil durumuna bakıyorum."
        is DeviceAction.Call -> "${a.target} için arama hazır; aramak için yeşil tuşa basın."
        is DeviceAction.Message -> "Mesaj hazır" + (a.target?.let { ", alıcı $it" } ?: "") + "; göndermek için gönder tuşuna basın."
        is DeviceAction.Navigate -> "${a.place} için harita açılıyor."
    }
}
