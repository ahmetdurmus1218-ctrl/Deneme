package io.github.ahmetdurmus1218.jarvis.core.scene

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Incremental parser for the scene JSON. Feed it model tokens as they stream; every object inside
 * the top-level "sahneler" array is emitted the moment its closing brace arrives, so TTS can start
 * speaking the first scene while the model is still writing the rest.
 *
 * Text before the first '{' (e.g. a ```json fence) is ignored.
 */
class SceneStreamParser(private val onScene: (Sahne) -> Unit) {

    sealed interface Outcome {
        data class Parsed(val reply: Reply) : Outcome
        /** The full text is not valid scene JSON. [scenesEmitted] scenes were already delivered. */
        data class Broken(val raw: String, val scenesEmitted: Int) : Outcome
    }

    private val buf = StringBuilder()
    private var pos = 0
    private var started = false
    private var rootStart = -1
    private var depth = 0
    private var inString = false
    private var escape = false
    private var stringStart = -1
    private var lastDepth1String: String? = null
    private var scenesArrayDepth = -1
    private var sceneStart = -1
    private var emitted = 0

    val scenesEmitted: Int get() = emitted
    val text: String get() = buf.toString()

    fun feed(chunk: String) {
        buf.append(chunk)
        while (pos < buf.length) {
            val c = buf[pos]
            if (!started) {
                if (c == '{') {
                    started = true
                    rootStart = pos
                } else {
                    pos++
                    continue
                }
            }
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> {
                        inString = false
                        if (depth == 1) lastDepth1String = buf.substring(stringStart + 1, pos)
                    }
                }
                pos++
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    stringStart = pos
                }
                '{' -> {
                    if (scenesArrayDepth >= 0 && depth == scenesArrayDepth) sceneStart = pos
                    depth++
                }
                '[' -> {
                    if (depth == 1 && lastDepth1String == "sahneler" && scenesArrayDepth < 0) scenesArrayDepth = depth + 1
                    depth++
                }
                '}' -> {
                    depth--
                    if (sceneStart >= 0 && depth == scenesArrayDepth) {
                        emitScene(buf.substring(sceneStart, pos + 1))
                        sceneStart = -1
                    }
                }
                ']' -> {
                    depth--
                    if (scenesArrayDepth >= 0 && depth == scenesArrayDepth - 1) scenesArrayDepth = -2 // closed; never reopen
                }
            }
            pos++
        }
    }

    private fun emitScene(json: String) {
        val sahne = try {
            SceneJson.decodeFromString(Sahne.serializer(), json)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        if (sahne.ses.isBlank() && sahne.gorsel == null) return
        emitted++
        onScene(sahne)
    }

    fun finish(): Outcome {
        val raw = buf.toString()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return Outcome.Broken(raw, emitted)
        val candidate = raw.substring(start, end + 1)
        return try {
            val obj: JsonObject = SceneJson.parseToJsonElement(candidate).jsonObject
            if (!obj.containsKey("sahneler")) return Outcome.Broken(raw, emitted)
            Outcome.Parsed(SceneJson.decodeFromJsonElement(Reply.serializer(), obj))
        } catch (_: SerializationException) {
            Outcome.Broken(raw, emitted)
        } catch (_: IllegalArgumentException) {
            Outcome.Broken(raw, emitted)
        }
    }

    companion object {
        /** Fallback when the JSON stays broken: show the plain text as a single scene. */
        fun plainTextScene(raw: String): Sahne {
            val cleaned = raw
                .replace(Regex("```[a-zA-Z]*"), "")
                .replace(Regex("[{}\\[\\]\"]"), " ")
                .replace(Regex("\\b(sahneler|ses|gorsel|detay|kaynaklar|tip)\\s*:"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            return Sahne(ses = cleaned.ifEmpty { "Bir sorun oluştu, cevabı okuyamadım." })
        }
    }
}
