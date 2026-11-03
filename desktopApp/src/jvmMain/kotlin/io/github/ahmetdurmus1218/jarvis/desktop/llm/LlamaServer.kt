package io.github.ahmetdurmus1218.jarvis.desktop.llm

import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.GenerationConfig
import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.llm.VisionLlm
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipFile

/** Talks to llama-server's OpenAI-compatible endpoint on 127.0.0.1 (streaming, server-sent events). */
class LlamaServerClient(private val baseUrl: String, private val http: HttpClient = HttpClient.newHttpClient()) {

    fun requestBody(messages: List<ChatMessage>, config: GenerationConfig): String = buildJsonObject {
        put("stream", true)
        // Reuse the unchanged start of the prompt (instructions, tools) between questions.
        put("cache_prompt", true)
        put("max_tokens", config.maxTokens)
        put("temperature", config.temperature.toDouble())
        put("messages", buildJsonArray {
            for (m in messages) {
                add(buildJsonObject {
                    put("role", when (m.role) {
                        Role.SYSTEM -> "system"
                        Role.ASSISTANT -> "assistant"
                        Role.USER, Role.TOOL -> "user"
                    })
                    put("content", m.content)
                })
            }
        })
        // Qwen 3.5 / Gemma 4 chat templates read this switch (llama-server runs with --jinja).
        put("chat_template_kwargs", buildJsonObject { put("enable_thinking", config.thinking) })
    }.toString()

    /** Like [requestBody], with [jpeg] attached to the last user message (OpenAI image_url, base64 data URL). */
    fun requestBodyWithImage(messages: List<ChatMessage>, jpeg: ByteArray, config: GenerationConfig): String {
        val base = kotlinx.serialization.json.Json.parseToJsonElement(requestBody(messages, config)).jsonObject
        val msgs = base["messages"]!!.jsonArray
        val lastUser = msgs.indexOfLast { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user" }
        val dataUrl = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg)
        val newMsgs = buildJsonArray {
            msgs.forEachIndexed { i, m ->
                if (i != lastUser) {
                    add(m)
                } else {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", m.jsonObject["content"]!!) })
                            add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", dataUrl) }) })
                        })
                    })
                }
            }
        }
        return JsonObject(base + ("messages" to newMsgs) + ("chat_template_kwargs" to buildJsonObject { put("enable_thinking", false) })).toString()
    }

    fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = streamBody(requestBody(messages, config))

    fun streamWithImage(messages: List<ChatMessage>, jpeg: ByteArray, config: GenerationConfig): Flow<String> =
        streamBody(requestBodyWithImage(messages, jpeg, config))

    private fun streamBody(body: String): Flow<String> = flow {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofLines())
        if (resp.statusCode() != 200) {
            val body = resp.body().use { it.toList().joinToString(" ").take(300) }
            error("llama-server HTTP ${resp.statusCode()}: $body")
        }
        resp.body().use { lines ->
            for (line in lines.iterator()) {
                if (line == "data: [DONE]") break
                parseSseLine(line)?.let { if (it.isNotEmpty()) emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun healthy(): Boolean = runCatching {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/health")).timeout(Duration.ofSeconds(2)).GET().build()
        http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Answer text in one SSE line, or null. Reasoning ("thinking") text is never returned. */
        fun parseSseLine(line: String): String? {
            if (!line.startsWith("data:")) return null
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return null
            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
            val choice = (obj["choices"]?.jsonArray?.firstOrNull() as? JsonObject) ?: return null
            val delta = choice["delta"] as? JsonObject ?: return null
            return (delta["content"] as? JsonPrimitive)?.contentOrNull
        }
    }
}

/**
 * Owns the llama-server process: extracts the hash-verified llama.cpp zip once, starts the server on
 * 127.0.0.1 with the chosen GGUF model (all layers on the GPU through Vulkan) and stops it when the
 * app exits or the model changes.
 */
class LlamaServerEngine(
    val model: LockedItem,
    private val modelPath: String,
    private val serverZip: File,
    private val binDir: File,
    /** The model's vision projector (mmproj GGUF), when installed: enables "ekranda ne var / bu resim". */
    val mmprojPath: String? = null,
) : LlmEngine, VisionLlm, AutoCloseable {
    override val id: String = model.title.substringBefore(" (") + " · llama.cpp"
    val serverZipName: String get() = serverZip.name
    private val lock = Mutex()
    @Volatile private var process: Process? = null
    @Volatile private var client: LlamaServerClient? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    private fun serverExecutable(): File {
        val target = unpackOnce(serverZip, binDir)
        return target.walkTopDown().firstOrNull { it.isFile && (it.name == "llama-server.exe" || it.name == "llama-server") }
            ?.also { it.setExecutable(true) }
            ?: error("llama-server bulunamadı (${serverZip.name})")
    }

    private suspend fun ensureStarted(): LlamaServerClient = lock.withLock {
        val running = process
        client?.let { if (running != null && running.isAlive) return it }
        val exe = serverExecutable()
        val port = ServerSocket(0).use { it.localPort }
        val p = ProcessBuilder(
            exe.absolutePath, "-m", modelPath,
            "--host", "127.0.0.1", "--port", port.toString(),
            "-ngl", "99", "-c", "8192", "--jinja",
            *(mmprojPath?.let { arrayOf("--mmproj", it) } ?: emptyArray()),
        ).directory(exe.parentFile).redirectErrorStream(true).redirectOutput(File(binDir, "llama-server.log")).start()
        process = p
        val c = LlamaServerClient("http://127.0.0.1:$port")
        val deadline = System.currentTimeMillis() + 180_000
        while (!c.healthy()) {
            if (!p.isAlive) error("llama-server kapandı (ayrıntı: ${File(binDir, "llama-server.log").absolutePath})")
            if (System.currentTimeMillis() > deadline) {
                p.destroy()
                error("llama-server 3 dakikada hazır olmadı")
            }
            kotlinx.coroutines.delay(500)
        }
        client = c
        c
    }

    private val warmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Starts llama-server when the app opens, so the first question does not wait for the model to load. */
    override fun warmUp() {
        if (client == null) warmScope.launch { runCatching { ensureStarted() } }
    }

    override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = flow {
        emitAll(ensureStarted().stream(messages, config))
    }.flowOn(Dispatchers.IO)

    override fun canSee(): Boolean = mmprojPath != null

    override fun streamWithImage(messages: List<ChatMessage>, jpeg: ByteArray, config: GenerationConfig): Flow<String> = flow {
        emitAll(ensureStarted().streamWithImage(messages, jpeg, config))
    }.flowOn(Dispatchers.IO)

    override fun close() {
        process?.let { p ->
            p.descendants().forEach { it.destroy() }
            p.destroy()
        }
        process = null
        client = null
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<String>.emitAll(f: Flow<String>) = f.collect { emit(it) }

    companion object {
        /** Unpacks a verified program zip into binDir/<zip name> once; returns that folder. */
        fun unpackOnce(zip: File, binDir: File): File {
            val target = File(binDir, zip.nameWithoutExtension).apply { mkdirs() }
            val marker = File(target, ".extracted")
            if (!marker.isFile || marker.readText() != zip.name) {
                extractZip(zip, target)
                marker.writeText(zip.name)
            }
            return target
        }

        /** Extracts [zip] into [target]; entries that would land outside [target] ("zip slip") are refused. */
        fun extractZip(zip: File, target: File) {
            val root = target.canonicalPath + File.separator
            ZipFile(zip).use { z ->
                for (entry in z.entries()) {
                    val out = File(target, entry.name).canonicalFile
                    require(out.path.startsWith(root)) { "Geçersiz zip girdisi: ${entry.name}" }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        z.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
        }
    }
}
