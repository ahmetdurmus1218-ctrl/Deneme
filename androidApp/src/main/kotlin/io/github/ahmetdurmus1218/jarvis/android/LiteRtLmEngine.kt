package io.github.ahmetdurmus1218.jarvis.android

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
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
import io.github.ahmetdurmus1218.jarvis.core.llm.VisionLlm
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemma 4 through Google's LiteRT-LM, fully on device. Tries the GPU backend first and falls back
 * to the CPU. Loading takes a few seconds; it starts when the app opens ([warmUp]).
 */
class LiteRtLmEngine(
    private val context: Context,
    val item: LockedItem,
    private val modelPath: String,
    private val onLoadFailed: (LockedItem, Throwable) -> Unit,
) : LlmEngine, VisionLlm {
    @Volatile private var engine: Engine? = null
    @Volatile private var backendName: String = ""
    private val lock = Mutex()

    override val id: String
        get() = item.title.substringBefore(" (") + if (backendName.isNotEmpty()) " · $backendName" else ""

    /** Whether the loaded engine has Gemma's vision part (null until the first load). */
    @Volatile private var visionLoaded: Boolean? = null

    private val warmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Loads the model right after start, so the first question is not kept waiting for it. */
    override fun warmUp() {
        if (engine == null) warmScope.launch { runCatching { ensureLoaded() } }
    }

    private suspend fun ensureLoaded(): Engine = lock.withLock {
        engine?.let { return it }
        // Speed first: the GPU (with the vision part if it fits there, else text only), then the
        // CPU with text only. Vision on the CPU is loaded later, only when a picture is asked about
        // (see ensureVision), because it makes every ordinary answer slower.
        val attempts = listOf(true to Backend.GPU(), false to Backend.GPU(), false to Backend.CPU())
        load(attempts)?.let { return it }
        val error = lastError ?: IllegalStateException("bilinmeyen hata")
        onLoadFailed(item, error)
        throw IllegalStateException("${item.title} yüklenemedi (bellek yetersiz olabilir). Bir sonraki soruda daha küçük model denenecek.", error)
    }

    @Volatile private var lastError: Throwable? = null

    private fun load(attempts: List<Pair<Boolean, Backend>>): Engine? {
        for ((withVision, backend) in attempts) {
            var e: Engine? = null
            try {
                e = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        visionBackend = if (withVision) backend else null,
                        maxNumImages = if (withVision) 1 else null,
                        cacheDir = context.cacheDir.path,
                    ),
                )
                e.initialize()
                engine = e
                backendName = backend.name
                visionLoaded = withVision
                return e
            } catch (t: Throwable) {
                // Free whatever a half-initialised engine holds before the next attempt.
                runCatching { e?.close() }
                lastError = t
            }
        }
        return null
    }

    /** An engine with the vision part: reloads on the CPU with it when the fast engine has none. */
    private suspend fun ensureVision(): Engine {
        val e = ensureLoaded()
        if (visionLoaded == true) return e
        return lock.withLock {
            engine?.takeIf { visionLoaded == true }?.let { return it }
            // Never close an engine a cancelled answer may still be generating with.
            val until = System.currentTimeMillis() + 10_000
            while (inUse.get() > 0 && System.currentTimeMillis() < until) kotlinx.coroutines.delay(50)
            runCatching { engine?.close() }
            engine = null
            load(listOf(true to Backend.CPU()))
                ?: (load(listOf(false to Backend.GPU(), false to Backend.CPU())).also { visionNotPossible = true }
                    ?: throw IllegalStateException("${item.title} yeniden yüklenemedi", lastError))
        }
    }

    private val inUse = java.util.concurrent.atomic.AtomicInteger(0)

    /** The vision part could not be loaded on this phone at all. */
    @Volatile private var visionNotPossible = false

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
        inUse.incrementAndGet()
        val conversation = try {
            runCatching { e.createConversation(conversationConfig(ThinkingConfig(enableThinking = config.thinking))) }
                .getOrElse { e.createConversation(conversationConfig(null)) }
        } catch (t: Throwable) {
            inUse.decrementAndGet()
            throw t
        }
        try {
            val tracker = DeltaTracker()
            conversation.sendMessageAsync(Message.user(last)).collect { m ->
                val delta = tracker.next(m.toString())
                if (delta.isNotEmpty()) emit(delta)
            }
        } finally {
            runCatching { conversation.close() }
            inUse.decrementAndGet()
        }
    }.flowOn(Dispatchers.IO)

    override fun canSee(): Boolean = !visionNotPossible

    override fun streamWithImage(messages: List<ChatMessage>, jpeg: ByteArray, config: GenerationConfig): Flow<String> = flow {
        val e = ensureVision()
        if (visionLoaded != true) error("Bu model bu telefonda görüntüyle çalışamadı")
        val system = messages.firstOrNull { it.role == Role.SYSTEM }?.content
        val question = messages.lastOrNull { it.role == Role.USER }?.content.orEmpty()
        val conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = system?.let { Contents.of(it) },
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
                maxOutputToken = config.maxTokens,
            ),
        )
        try {
            val tracker = DeltaTracker()
            conversation.sendMessageAsync(Message.user(Contents.of(Content.ImageBytes(jpeg), Content.Text(question)))).collect { m ->
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
