package io.github.ahmetdurmus1218.jarvis.core.scene

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Model reply format (halilozat/jarvis idea):
 * {"sahneler":[{"ses":"1-2 kısa cümle","gorsel":{...}}],"detay":"markdown","kaynaklar":[...]}
 */
@Serializable
data class Reply(
    val sahneler: List<Sahne> = emptyList(),
    val detay: String? = null,
    val kaynaklar: List<Kaynak> = emptyList(),
)

@Serializable
data class Sahne(
    /** Text that is spoken: one or two short sentences. */
    val ses: String = "",
    val gorsel: Gorsel? = null,
)

/**
 * Visual card. `tip` is one of [GorselTip]; the other fields are used depending on the type:
 * baslik → baslik; sayi → deger (+birim, etiket); liste/adimlar → ogeler; alinti → metin (+kaynak);
 * kaynak_karti → baslik, kaynak, konum.
 */
@Serializable
data class Gorsel(
    val tip: String = GorselTip.YOK,
    val baslik: String? = null,
    val deger: String? = null,
    val birim: String? = null,
    val etiket: String? = null,
    val ogeler: List<String>? = null,
    val metin: String? = null,
    val kaynak: String? = null,
    val konum: String? = null,
)

object GorselTip {
    const val YOK = "yok"
    const val BASLIK = "baslik"
    const val SAYI = "sayi"
    const val LISTE = "liste"
    const val ADIMLAR = "adimlar"
    const val ALINTI = "alinti"
    const val KAYNAK_KARTI = "kaynak_karti"
    val ALL = setOf(YOK, BASLIK, SAYI, LISTE, ADIMLAR, ALINTI, KAYNAK_KARTI)
}

@Serializable
data class Kaynak(
    val baslik: String = "",
    /** "wikipedia", "belge", "web", "arac" */
    val tur: String? = null,
    /** Article title, file name or tool name. */
    val konum: String? = null,
)

/** A scene after verification (section 5.4). */
data class VerifiedScene(
    val sahne: Sahne,
    /** True when the visual was replaced because its value/quote was not found in the evidence. */
    val downgraded: Boolean = false,
    /** True when the scene states something that no source backs ("kaynak yok" label). */
    val kaynakYok: Boolean = false,
)

data class VerifiedReply(
    val scenes: List<VerifiedScene>,
    val detay: String?,
    val kaynaklar: List<Kaynak>,
)

val SceneJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}
