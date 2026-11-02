package io.github.ahmetdurmus1218.jarvis.core.netlog

import kotlinx.serialization.Serializable

/** One network event, shown in the "Ağ kayıt defteri" screen (section 3.3). */
@Serializable
data class NetworkEvent(
    val timeMillis: Long,
    val host: String,
    val bytes: Long,
    /** What was fetched (file name) or "web araması". */
    val what: String,
    /** How it was approved: "biyometrik", "PIN", "onay penceresi", "UAC". */
    val approval: String,
    /** "tamam", "reddedildi", "hash uyuşmadı", "hata: ..." */
    val result: String,
)
