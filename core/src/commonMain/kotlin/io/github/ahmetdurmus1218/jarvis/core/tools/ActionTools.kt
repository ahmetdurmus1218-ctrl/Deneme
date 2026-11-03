package io.github.ahmetdurmus1218.jarvis.core.tools

import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Device actions for the model (section 5.2): alarm, timer, opening an app. They run through the
 * same platform executor as the instant quick commands (plain Intents on Android; phone / SMS apps
 * still ask for confirmation there). As side-effect tools they are refused once Wikipedia, document
 * or web text has entered the conversation, so text inside an article can never set an alarm or
 * open an app (section 5.6).
 */
class ActionTools(private val execute: suspend (QuickCommand) -> String?) {
    fun all(): List<Tool> = listOf(AlarmKur(), Zamanlayici(), UygulamaAc())

    private fun result(error: String?, done: String): ToolOutput =
        if (error == null) {
            ToolOutput(buildJsonObject { put("tamam", true); put("yapildi", done) }.toString())
        } else {
            ToolOutput(buildJsonObject { put("tamam", false); put("hata", error) }.toString(), ok = false)
        }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    inner class AlarmKur : Tool {
        override val name = "alarm_kur"
        override val description = "Telefonun saat uygulamasında alarm kurar."
        override val parameters = "{\"saat\": \"SS:DD\", \"not\": \"metin (isteğe bağlı)\"}"
        override val trust = Trust.COMPUTED
        override val sideEffects = true
        override suspend fun run(input: JsonObject): ToolOutput {
            val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(input.str("saat")?.trim().orEmpty())
                ?: return ToolOutput("{\"hata\":\"saat SS:DD biçiminde olmalı\"}", ok = false)
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h !in 0..23 || min !in 0..59) return ToolOutput("{\"hata\":\"geçersiz saat\"}", ok = false)
            val label = input.str("not")?.take(60)
            val hhmm = "${h.toString().padStart(2, '0')}:${min.toString().padStart(2, '0')}"
            return result(execute(QuickCommand.SetAlarm(h, min, label)), "alarm $hhmm")
        }
    }

    inner class Zamanlayici : Tool {
        override val name = "zamanlayici"
        override val description = "Geri sayım zamanlayıcısı başlatır (en fazla 24 saat)."
        override val parameters = "{\"saniye\": sayı}"
        override val trust = Trust.COMPUTED
        override val sideEffects = true
        override suspend fun run(input: JsonObject): ToolOutput {
            val s = input["saniye"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
                ?: return ToolOutput("{\"hata\":\"saniye gerekli\"}", ok = false)
            if (s !in 1..86_400) return ToolOutput("{\"hata\":\"süre 1 saniye ile 24 saat arasında olmalı\"}", ok = false)
            return result(execute(QuickCommand.StartTimer(s)), "zamanlayıcı $s saniye")
        }
    }

    inner class UygulamaAc : Tool {
        override val name = "uygulama_ac"
        override val description = "Telefondaki bir uygulamayı adıyla açar (arama/mesaj uygulamaları için kullanıcı onayı istenir)."
        override val parameters = "{\"ad\": \"uygulama adı\"}"
        override val trust = Trust.COMPUTED
        override val sideEffects = true
        override suspend fun run(input: JsonObject): ToolOutput {
            val name = input.str("ad")?.trim().orEmpty()
            if (name.isEmpty() || name.length > 40) return ToolOutput("{\"hata\":\"uygulama adı gerekli\"}", ok = false)
            return result(execute(QuickCommand.OpenApp(name.lowercase())), "açıldı: $name")
        }
    }
}
