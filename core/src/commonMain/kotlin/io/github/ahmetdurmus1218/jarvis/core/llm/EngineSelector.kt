package io.github.ahmetdurmus1218.jarvis.core.llm

import kotlinx.coroutines.flow.Flow

/**
 * Picks the engine for every request: the platform's real model when one is installed and
 * loadable, otherwise the mock. The choice is re-evaluated per request, so a model installed by
 * the wizard is used from the next question on, without restarting the app.
 */
class EngineSelector(
    private val fallback: LlmEngine,
    private val pick: () -> LlmEngine?,
) : LlmEngine {
    val current: LlmEngine get() = pick() ?: fallback

    override val id: String get() = current.id

    override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = current.stream(messages, config)

    /** The current model if it can see images right now (vision part installed and loaded). */
    val vision: VisionLlm? get() = (current as? VisionLlm)?.takeIf { it.canSee() }
}

/**
 * A model that also takes an image (Gemma 4 through LiteRT-LM on the phone; a GGUF model with its
 * vision projector through llama-server on the PC). The image never leaves the device.
 */
interface VisionLlm {
    /** True when the vision part is available (e.g. the projector file is installed). */
    fun canSee(): Boolean
    /** [messages] ends with the user's question; [jpeg] is the picture it is about. */
    fun streamWithImage(messages: List<ChatMessage>, jpeg: ByteArray, config: GenerationConfig): Flow<String>
}

/**
 * Turns a stream that may resend the whole text so far into a stream of new text only. The style
 * is decided once, on the second chunk, so a delta that happens to repeat earlier text later in
 * the answer is never mistaken for a resend.
 */
class DeltaTracker {
    private val sb = StringBuilder()
    private var chunks = 0
    private var accumulated = false

    fun next(chunk: String): String {
        if (chunk.isEmpty()) return ""
        chunks++
        val soFar = sb.toString()
        if (chunks == 2) accumulated = chunk.length > soFar.length && chunk.startsWith(soFar)
        val delta = if (accumulated && chunk.startsWith(soFar)) chunk.substring(soFar.length) else chunk
        if (accumulated) {
            sb.setLength(0)
            sb.append(chunk)
        } else {
            sb.append(delta)
        }
        return delta
    }
}
