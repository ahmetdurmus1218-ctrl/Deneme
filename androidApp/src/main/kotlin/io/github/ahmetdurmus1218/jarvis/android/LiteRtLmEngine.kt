package io.github.ahmetdurmus1218.jarvis.android

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.DeltaTracker
import io.github.ahmetdurmus1218.jarvis.core.llm.GenerationConfig
import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemma 4 through Google's LiteRT-LM, fully on device. Tries the GPU backend first and falls back
 * to the CPU. Loading takes a few seconds and happens on the first question.
 */
class LiteRtLmEngine(
    private val context: Context,
    val item: LockedItem,
    private val modelPath: String,
    private val onLoadFailed: (LockedItem, Throwable) -> Unit,
) : LlmEngine {
    @Volatile private var engine: Engine? = null
    @Volatile private var backendName: String = ""
    private val lock = Mutex()

    override val id: String
        get() = item.title.substringBefore(" (") + if (backendName.isNotEmpty()) " · $backendName" else ""

    private suspend fun ensureLoaded(): Engine = lock.withLock {
        engine?.let { return it }
        var last: Throwable? = null
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            try {
                val e = Engine(EngineConfig(modelPath = modelPath, backend = backend, cacheDir = context.cacheDir.path))
                e.initialize()
                engine = e
                backendName = backend.name
                return e
            } catch (t: Throwable) {
                last = t
            }
        }
        val error = last ?: IllegalStateException("bilinmeyen hata")
        onLoadFailed(item, error)
        throw IllegalStateException("${item.title} yüklenemedi (bellek yetersiz olabilir). Bir sonraki soruda daha küçük model denenecek.", error)
    }

    override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = flow {
        val e = ensureLoaded()
        val system = messages.firstOrNull { it.role == Role.SYSTEM }?.content
        val rest = messages.filter { it.role != Role.SYSTEM }
        val history = rest.dropLast(1).map { m ->
            if (m.role == Role.ASSISTANT) Message.model(m.content) else Message.user(m.content)
        }
        val last = rest.lastOrNull()?.content.orEmpty()
        fun conversationConfig(thinking: ThinkingConfig?) = ConversationConfig(
            systemInstruction = system?.let { Contents.of(it) },
            initialMessages = history,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = config.temperature.toDouble()),
            maxOutputToken = config.maxTokens,
            thinkingConfig = thinking,
        )
        // Thinking mode is optional per model: retry without it if the model refuses.
        val conversation = runCatching { e.createConversation(conversationConfig(ThinkingConfig(enableThinking = config.thinking))) }
            .getOrElse { e.createConversation(conversationConfig(null)) }
        try {
            val tracker = DeltaTracker()
            conversation.sendMessageAsync(Message.user(last)).collect { m ->
                val delta = tracker.next(m.toString())
                if (delta.isNotEmpty()) emit(delta)
            }
        } finally {
            runCatching { conversation.close() }
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}

/**
 * Chooses the Android model: the one picked in Settings, else Gemma 4 E4B when the phone has at
 * least 12 GB RAM, else E2B. A model that failed to load is skipped for the rest of the session.
 */
class AndroidLlm(private val context: Context, private val storage: AndroidModelStorage) {
    private val failed = mutableSetOf<String>()
    private var cached: LiteRtLmEngine? = null

    private val ramGb: Double by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1_073_741_824.0
    }

    @Synchronized
    fun pick(registry: ModelRegistry, chosenId: String?): LlmEngine? {
        val installed = registry.forPlatform(Platform.ANDROID)
            .filter { it.kind == "llm" && it.id !in failed && storage.isInstalled(it) }
        if (installed.isEmpty()) return null
        val choice = installed.firstOrNull { it.id == chosenId }
            ?: installed.firstOrNull { it.group == "llm-android-e4b" && ramGb >= 11.5 }
            ?: installed.firstOrNull { it.group == "llm-android-e2b" }
            ?: installed.first()
        if (cached?.item?.id != choice.id) {
            cached?.close()
            cached = LiteRtLmEngine(context, choice, storage.pathOf(choice)) { item, _ ->
                synchronized(this) {
                    failed += item.id
                    cached = null
                }
            }
        }
        return cached
    }
}
