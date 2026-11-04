package io.github.ahmetdurmus1218.jarvis.core.assistant

import io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction
import io.github.ahmetdurmus1218.jarvis.core.device.DeviceCommands
import io.github.ahmetdurmus1218.jarvis.core.device.MediaKey
import io.github.ahmetdurmus1218.jarvis.core.device.VolumeChange
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish

/**
 * Something Rick proposes and does only after the user says yes: a device action for a need said in
 * passing ("yarın 7'de kalkmam lazım" → an alarm), or looking something up on the internet when the
 * offline library has nothing. Nothing happens until the user answers.
 */
sealed interface Offer {
    /** A device action (alarm, torch, music, silent mode, preparing a call). */
    data class Action(val command: QuickCommand) : Offer

    /**
     * One search on the Turkish Wikipedia website. [query] is the only text that leaves the device;
     * it is shown to the user before the approval.
     */
    data class WebLookup(val query: String, val question: String) : Offer

    /** "Günün haberlerini okuyayım mı?" (offered after waking up). */
    data object News : Offer

    /** "Hangisini açayım?": the user's next sentence is what to open in [app]. */
    data class AskWhat(val app: io.github.ahmetdurmus1218.jarvis.core.device.OutsideApp) : Offer
}

/** "evet", "olur", "tabii", "yap" … / "hayır", "gerek yok", "boşver" … as an answer to an offer. */
object OfferReply {
    private val YES = Regex(
        "^(?:evet|olur|tabii|tabi|tamam|peki|hadi|yap|yapalim|ac|kur|bak|ara|lutfen|isterim|elbette|aynen|olsun|hay hay|" +
            "evet lutfen|tamam yap|olur yap|evet yap|evet bak|tabii ki|tabiki|ok|okey|e tabi|kesinlikle|harika)" +
            "(?: (?:lutfen|olur|yap|bak|ac|kur|ara|hadi|tabii|tamam|evet|rick))*$",
    )
    private val NO = Regex(
        "^(?:hayir|yok|gerek yok|bosver|bos ver|istemem|istemiyorum|olmaz|vazgec|vazgectim|sonra|simdi degil|kalsin|yapma|dur)" +
            "(?: (?:tesekkurler|sagol|gerek yok|kalsin|simdilik|rick))*$",
    )

    /**
     * Longer answers count only when every word is answer vocabulary ("onay verdim, bak", "izin
     * veriyorum hadi", "online ol bak"): "tamam, alarm kur" is a new command, not a yes.
     */
    private val STRONG_YES = setOf("evet", "olur", "tamam", "onay", "onayliyorum", "onayladim", "izin", "iznim", "hadi", "tabii", "tabi", "elbette", "kesinlikle", "aynen", "online", "cevrimici", "internete", "internetten", "internette", "interneti")
    private val YES_VOCAB = STRONG_YES + setOf(
        "lutfen", "yap", "bak", "baksana", "bakabilirsin", "ara", "arastir", "ac", "kur", "verdim", "veriyorum", "verdi", "ver", "var", "ol", "cik", "cikabilirsin",
        "kullan", "baglan", "gir", "rick", "ben", "sana", "artik", "simdi", "hemen", "bunu", "onu", "sunu", "de", "da", "ve", "o", "zaman", "ki", "peki", "ok", "okey", "yapabilirsin", "devam", "et",
    )
    private val STRONG_NO = setOf("hayir", "istemiyorum", "istemem", "yapma", "bakma", "vazgec", "vazgectim", "olmaz", "bosver", "gerek")
    private val NO_VOCAB = STRONG_NO + setOf("yok", "simdi", "degil", "simdilik", "tesekkurler", "tesekkur", "ederim", "sagol", "rick", "lutfen", "kalsin", "sonra", "bunu", "onu", "artik", "ben", "de", "da", "ama", "cikma", "internete", "arama", "acma")

    /** true = yes, false = no, null = something else (a new question or command). */
    fun parse(text: String): Boolean? {
        val n = Turkish.normalize(text).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
        if (YES.matches(n)) return true
        if (NO.matches(n)) return false
        if (text.trim().endsWith("?")) return null
        val words = n.split(' ')
        if (words.size > 10) return null
        if (words.all { it in YES_VOCAB } && words.any { it in STRONG_YES }) return true
        if (words.all { it in NO_VOCAB } && words.any { it in STRONG_NO }) return false
        return null
    }
}

/**
 * Needs said in ordinary conversation that Rick can help with on the spot. Each one only becomes an
 * offer ("… ayım mı?"); explicit commands ("alarm kur") are handled by the quick commands instead.
 */
object NeedDetector {
    data class Need(val offer: Offer.Action, val say: String)

    private val WAKE = Regex(
        "\\b(\\d{1,2})(?:[:.](\\d{2}))?(?:da|de|ta|te)?\\b(?:(?!\\d).){0,20}?\\b(?:kalk|uyan)(?:mam|mamiz|maliyim|maliyiz|acagim|acam|mak zorundayim|mam lazim|mam gerek)",
    )
    private val DARK = Regex("\\b(?:(?:cok|her yer|burasi|ortalik|oda) )?karanlik(?: oldu)?\\b|\\bhicbir sey gormuyorum\\b|\\bgoremiyorum\\b")
    private val BORED = Regex("\\b(?:sikildim|canim (?:cok |biraz )?(?:sikiliyor|sikkin)|moralim (?:cok |biraz )?bozuk|keyifsizim|keyfim yok|bunaldim)\\b")
    private val SLEEP = Regex("\\b(?:uyuyacagim|uyuyacam|yatiyorum|yatacagim|yatacam|uyumam lazim|uyumaya gidiyorum|iyi geceler)\\b")
    private val CALL = Regex("(?i)(\\S+)\\s+aramam\\s+(?:lazım|lazim|gerek|gerekiyor|lazımdı|lazimdi)")

    fun detect(text: String): Need? {
        val n = Turkish.normalize(text)
        if (text.trim().endsWith("?")) return null
        WAKE.find(n)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            // "akşam 8'de" is 20:00; a plain "7'de kalkmam lazım" is the morning.
            if (Regex("\\b(?:aksam|gece)\\b").containsMatchIn(n) && h in 1..11) h += 12
            if (h in 0..23 && min in 0..59) {
                val t = "${h.toString().padStart(2, '0')}:${min.toString().padStart(2, '0')}"
                return Need(Offer.Action(QuickCommand.SetAlarm(h, min)), "Saat $t için alarm kurayım mı?")
            }
        }
        CALL.find(text)?.let { m ->
            val name = DeviceCommands.contactName(m.groupValues[1])
            if (name.length >= 2 && Turkish.normalize(name) !in setOf("onu", "seni", "beni", "bunu", "birini")) {
                return Need(Offer.Action(QuickCommand.Device(DeviceAction.Call(name))), "$name aransın mı?")
            }
        }
        if (DARK.containsMatchIn(n)) return Need(Offer.Action(QuickCommand.Device(DeviceAction.Torch(true))), "Feneri açayım mı?")
        if (BORED.containsMatchIn(n)) return Need(Offer.Action(QuickCommand.Device(DeviceAction.Media(MediaKey.PLAY))), "Buna üzüldüm. Biraz müzik açayım mı, iyi gelebilir.")
        if (SLEEP.containsMatchIn(n)) return Need(Offer.Action(QuickCommand.Device(DeviceAction.Volume(VolumeChange.SILENT_PHONE))), "İyi uykular! Telefonu sessize alayım mı?")
        return null
    }
}

/**
 * The user asking Rick to look something up online in so many words: "internette Çanakkale Savaşı
 * ara", "bunu internetten bul", "online ol, onay verdim, bak". Each one still needs the one-time
 * approval (fingerprint / PIN, UAC on the PC) before anything leaves the device.
 */
object WebCommand {
    private const val NET = "(?:internette|internetten|internete|nette|netten|webde|webden|online|cevrimici)"
    private const val LOOK = "(?:ara|arat|bak|bul|arastir|sorgula|ogren)\\w*(?: misin| musun| bakalim| hemen| simdi| lutfen| rick)*"
    private val NET_FIRST = Regex("^(?:rick )?$NET\\s+(.+?)\\s+$LOOK$")
    private val NET_LAST = Regex("^(?:rick )?(.+?)\\s+$NET\\s+$LOOK$")
    private val NET_WORD = Regex("\\b(?:online|cevrimici|internet\\w*|net(?:te|ten|e)|web\\w*)\\b")
    /** The words of "go online and look" when nothing else is said. */
    private val GO = setOf(
        "online", "cevrimici", "ol", "internete", "internetten", "internette", "interneti", "internet", "nette", "netten", "webde", "webden", "web",
        "cik", "cikabilirsin", "cikmana", "bak", "baksana", "bakabilirsin", "ara", "arastir", "bul", "baglan", "kullan", "gir", "git",
        "onay", "onayliyorum", "onayladim", "verdim", "veriyorum", "verdi", "izin", "iznim", "var", "tamam", "evet", "hadi", "artik", "simdi",
        "hemen", "lutfen", "bunu", "onu", "sunu", "rick", "ben", "sana", "de", "da", "ve", "o", "zaman", "sen", "bir",
    )
    private val TOPIC_FILLER = setOf("bunu", "onu", "sunu", "bu", "su", "o", "konuyu", "simdi", "hemen", "lutfen", "bir", "artik")

    /** The words to search; "" when the user said only "go online and look" (use the last question); null when this is not such a request. */
    fun parse(text: String): String? {
        val n = Turkish.normalize(text).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
        if (n.isEmpty()) return null
        val words = n.split(' ')
        if (words.size <= 10 && words.all { it in GO } && NET_WORD.containsMatchIn(n)) return ""
        val m = NET_FIRST.matchEntire(n) ?: NET_LAST.matchEntire(n) ?: return null
        val part = m.groupValues[1]
        if (part.split(' ').all { it in TOPIC_FILLER }) return ""
        val said = Turkish.cutOriginal(text, part).split(Regex("\\s+"))
            .map { it.substringBefore('\'').substringBefore('’').trim(',', '.', '!', '"') }
            .filter { it.isNotBlank() && Turkish.normalize(it) !in TOPIC_FILLER }
        return said.joinToString(" ").take(OnlineWikipedia.MAX_QUERY).ifBlank { null }
    }
}

/**
 * Questions about right now (weather, exchange rates, news, scores): no offline library and no
 * encyclopedia knows them, so Rick offers to open the search in the browser instead of guessing.
 */
object LiveInfo {
    private val LIVE = Regex(
        "\\b(?:hava durumu\\w*|hava nasil|hava kac derece|disarisi kac derece|yagmur yagacak|kar yagacak|dolar\\w*|euro\\w*|avro\\w*|sterlin\\w*|doviz\\w*|kur(?:u|lari) ne|" +
            "altin fiyat\\w*|gram altin\\w*|ceyrek altin\\w*|bitcoin\\w*|borsa\\w*|bist|mac(?:i|in)? (?:sonucu|skoru|kac kac|ne oldu|ne zaman|saat kacta)|" +
            "skor\\w*|puan durumu|trafik durumu|yol durumu|nobetci eczane\\w*|benzin fiyat\\w*|akaryakit\\w*|namaz vakti\\w*|ezan\\w* ne zaman)\\b",
    )

    /** "Borsa nedir?" is a definition, not today's numbers. */
    private val DEFINITION = Regex("\\b(?:nedir|kimdir|ne demek|ne anlama gelir|nasil calisir|nasil olusur|tarihi|tarihcesi|kim buldu|kim icat)\\b")

    fun matches(text: String): Boolean = Turkish.normalize(text).let { LIVE.containsMatchIn(it) && !DEFINITION.containsMatchIn(it) }

    fun say(query: String) = "Bu anlık bir bilgi, internetsiz bilemem. Tarayıcıda Google'da \"$query\" aramasını açayım mı?"
}

/** A page from the Wikipedia website, verbatim (first paragraphs only). */
data class WebPage(val title: String, val text: String, val url: String)

/**
 * The one internet source Rick may ask to use: the Turkish Wikipedia's public API, one search per
 * approval. Only builds and checks the address and reads the answer; the request itself is made by
 * the gate (Kapı / rick-kapi), never by this module.
 */
object OnlineWikipedia {
    const val HOST = "tr.wikipedia.org"
    private const val PREFIX = "https://$HOST/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrsearch="
    private const val SUFFIX = "&gsrlimit=3&prop=extracts&exintro=1&explaintext=1&exlimit=3&redirects=1"

    /** Answers larger than this are refused (a normal answer is a few kilobytes). */
    const val MAX_BYTES = 200_000
    const val MAX_QUERY = 120

    /** Wikimedia asks every client to name itself; no personal data in it. */
    const val USER_AGENT = "RickKapi/1.3 (offline Turkish assistant; one approved search; https://github.com/ahmetdurmus1218-ctrl/Deneme)"

    fun searchUrl(query: String): String = PREFIX + encode(query.trim().take(MAX_QUERY)) + SUFFIX

    /** The search words of a [url] built by [searchUrl]; null for any other address. */
    fun queryOf(url: String): String? {
        if (!url.startsWith(PREFIX) || !url.endsWith(SUFFIX)) return null
        val enc = url.substring(PREFIX.length, url.length - SUFFIX.length)
        if (enc.isEmpty() || !Regex("^[A-Za-z0-9%._~-]+$").matches(enc)) return null
        return runCatching { decode(enc) }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= MAX_QUERY }
    }

    /** True only for addresses made by [searchUrl] (what both gates accept). */
    fun allowed(url: String): Boolean = queryOf(url) != null

    fun pageUrl(title: String) = "https://$HOST/wiki/" + encode(title.replace(' ', '_'))

    /**
     * Pages of an API answer, best match first: a title that is exactly the searched words, then
     * the search engine's own order. Empty when nothing was found or the text is not the API's.
     */
    fun parse(json: String, query: String = ""): List<WebPage> {
        val q = io.github.ahmetdurmus1218.jarvis.core.text.Turkish.normalize(query).trim()
        val pages = parseRaw(json)
        if (q.isEmpty()) return pages
        fun exact(p: WebPage) = io.github.ahmetdurmus1218.jarvis.core.text.Turkish.normalize(p.title).substringBefore(" (").trim() == q
        return pages.sortedBy { if (exact(it)) 0 else 1 }
    }

    private fun parseRaw(json: String): List<WebPage> {
        val root = runCatching { io.github.ahmetdurmus1218.jarvis.core.scene.SceneJson.parseToJsonElement(json) }.getOrNull()
            as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val pages = (root["query"] as? kotlinx.serialization.json.JsonObject)?.get("pages") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return pages.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            val title = str("title") ?: return@mapNotNull null
            val text = str("extract")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val index = (o["index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 99
            if (text.length < 20) null else Triple(index, title, text)
        }.sortedBy { it.first }.map { (_, title, text) -> WebPage(title, text.take(1_500), pageUrl(title)) }
    }

    /** RFC 3986 percent-encoding of UTF-8 bytes (unreserved characters kept). */
    fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~") append(ch)
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }

    private fun decode(s: String): String {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                out += s.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                out += c.code.toByte()
                i++
            }
        }
        return out.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }
}
