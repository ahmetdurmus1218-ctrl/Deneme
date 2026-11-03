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

    /** true = yes, false = no, null = something else (a new question). */
    fun parse(text: String): Boolean? {
        val n = Turkish.normalize(text).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
        return when {
            YES.matches(n) -> true
            NO.matches(n) -> false
            else -> null
        }
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
                return Need(Offer.Action(QuickCommand.Device(DeviceAction.Call(name))), "$name için aramayı hazırlayayım mı? Aramayı siz başlatırsınız.")
            }
        }
        if (DARK.containsMatchIn(n)) return Need(Offer.Action(QuickCommand.Device(DeviceAction.Torch(true))), "Feneri açayım mı?")
        if (BORED.containsMatchIn(n)) return Need(Offer.Action(QuickCommand.Device(DeviceAction.Media(MediaKey.PLAY))), "Buna üzüldüm. Biraz müzik açayım mı, iyi gelebilir.")
        if (SLEEP.containsMatchIn(n)) return Need(Offer.Action(QuickCommand.Device(DeviceAction.Volume(VolumeChange.SILENT_PHONE))), "İyi uykular! Telefonu sessize alayım mı?")
        return null
    }
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
    const val USER_AGENT = "RickKapi/1.1 (offline Turkish assistant; one approved search; https://github.com/ahmetdurmus1218-ctrl/Deneme)"

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

    /** Pages of an API answer, best match first; empty when nothing was found or the text is not the API's. */
    fun parse(json: String): List<WebPage> {
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
