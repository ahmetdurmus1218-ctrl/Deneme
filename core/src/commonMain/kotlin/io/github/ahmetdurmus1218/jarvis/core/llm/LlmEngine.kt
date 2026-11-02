package io.github.ahmetdurmus1218.jarvis.core.llm

import kotlinx.coroutines.flow.Flow

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatMessage(val role: Role, val content: String, val toolName: String? = null)

data class GenerationConfig(
    val maxTokens: Int = 512,
    val thinking: Boolean = false,
    val temperature: Float = 0.6f,
)

/** A local LLM backend (LiteRT-LM on Android, llama-server on Windows, or the mock). */
interface LlmEngine {
    /** Short identifier shown in the UI, e.g. "mock", "gemma-4-e4b". */
    val id: String

    /** Streams raw text tokens of the assistant reply. Thinking text must not be emitted. */
    fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String>
}

/** Removes a leading <think>…</think> block from a token stream (models that inline reasoning). */
class ThinkStripper {
    private val pending = StringBuilder()
    private var state = 0 // 0 = undecided, 1 = inside think, 2 = passthrough

    fun feed(chunk: String): String {
        if (state == 2) return chunk
        pending.append(chunk)
        val s = pending.toString()
        if (state == 0) {
            val trimmed = s.trimStart()
            if (trimmed.length < OPEN.length && OPEN.startsWith(trimmed)) return ""
            if (!trimmed.startsWith(OPEN)) {
                state = 2
                pending.clear()
                return s
            }
            state = 1
        }
        val end = s.indexOf(CLOSE)
        if (end < 0) return ""
        state = 2
        pending.clear()
        return s.substring(end + CLOSE.length)
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
