package io.github.ahmetdurmus1218.jarvis.desktop

import com.sun.net.httpserver.HttpServer
import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.GenerationConfig
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelLock
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.desktop.llm.DesktopLlm
import io.github.ahmetdurmus1218.jarvis.desktop.llm.LlamaServerClient
import io.github.ahmetdurmus1218.jarvis.desktop.llm.LlamaServerEngine
import io.github.ahmetdurmus1218.jarvis.ui.ImportOutcome
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LlamaServerTest {

    @Test
    fun sseLinesYieldOnlyAnswerText() {
        assertEquals("Merhaba", LlamaServerClient.parseSseLine("""data: {"choices":[{"delta":{"content":"Merhaba"}}]}"""))
        assertEquals(" efendim", LlamaServerClient.parseSseLine("""data:{"choices":[{"index":0,"delta":{"content":" efendim"}}]}"""))
        // Thinking text, role-only deltas, the end marker, comments and junk produce nothing.
        assertNull(LlamaServerClient.parseSseLine("""data: {"choices":[{"delta":{"reasoning_content":"düşünüyorum"}}]}"""))
        assertNull(LlamaServerClient.parseSseLine("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(LlamaServerClient.parseSseLine("""data: {"choices":[{"delta":{"content":null}}]}"""))
        assertNull(LlamaServerClient.parseSseLine("data: [DONE]"))
        assertNull(LlamaServerClient.parseSseLine(": keep-alive"))
        assertNull(LlamaServerClient.parseSseLine("data: {bozuk"))
        assertNull(LlamaServerClient.parseSseLine(""))
    }

    @Test
    fun requestCarriesRolesLimitsAndThinkingSwitch() {
        val body = LlamaServerClient("http://127.0.0.1:1").requestBody(
            listOf(
                ChatMessage(Role.SYSTEM, "Sen Jarvis'sin."),
                ChatMessage(Role.USER, "Merhaba"),
                ChatMessage(Role.ASSISTANT, "Buyurun"),
                ChatMessage(Role.TOOL, "araç çıktısı"),
            ),
            GenerationConfig(maxTokens = 321, thinking = true),
        )
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(true, obj["stream"]!!.jsonPrimitive.boolean)
        assertEquals("321", obj["max_tokens"]!!.jsonPrimitive.content)
        assertEquals(listOf("system", "user", "assistant", "user"), obj["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(true, obj["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun streamsFromAnOpenAiCompatibleServer() = runBlocking {
        var received = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            received = ex.requestBody.readBytes().decodeToString()
            val sse = buildString {
                append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n")
                append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm\"}}]}\n\n")
                append("data: {\"choices\":[{\"delta\":{\"content\":\"Merhaba \"}}]}\n\n")
                append("data: {\"choices\":[{\"delta\":{\"content\":\"efendim.\"}}]}\n\n")
                append("data: [DONE]\n\n")
                append("data: {\"choices\":[{\"delta\":{\"content\":\"SONRA\"}}]}\n\n")
            }.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, sse.size.toLong())
            ex.responseBody.use { it.write(sse) }
        }
        server.createContext("/health") { ex ->
            ex.sendResponseHeaders(200, 2)
            ex.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        try {
            val client = LlamaServerClient("http://127.0.0.1:${server.address.port}")
            assertTrue(client.healthy())
            val parts = client.stream(listOf(ChatMessage(Role.USER, "Selam")), GenerationConfig()).toList()
            assertEquals(listOf("Merhaba ", "efendim."), parts)
            assertTrue("Selam" in received)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun serverErrorsBecomeReadableMessages() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val b = "model yok".toByteArray()
            ex.sendResponseHeaders(503, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val e = assertFailsWith<IllegalStateException> {
                LlamaServerClient("http://127.0.0.1:${server.address.port}").stream(listOf(ChatMessage(Role.USER, "x")), GenerationConfig()).toList()
            }
            assertTrue("503" in e.message!! && "model yok" in e.message!!)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun nothingListeningIsNotHealthy() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertFalse(LlamaServerClient("http://127.0.0.1:$port").healthy())
    }

    @Test
    fun zipSlipIsRefused() {
        val dir = Files.createTempDirectory("llama").toFile()
        try {
            val evil = File(dir, "evil.zip")
            ZipOutputStream(evil.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("../disari.txt")); z.write(1); z.closeEntry()
            }
            assertFailsWith<IllegalArgumentException> { LlamaServerEngine.extractZip(evil, File(dir, "out")) }
            assertFalse(File(dir, "disari.txt").exists())

            val good = File(dir, "good.zip")
            ZipOutputStream(good.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("build/bin/llama-server.exe")); z.write("x".toByteArray()); z.closeEntry()
            }
            LlamaServerEngine.extractZip(good, File(dir, "ok"))
            assertTrue(File(dir, "ok/build/bin/llama-server.exe").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun item(id: String, kind: String, group: String, file: String) = LockedItem(
        id = id, kind = kind, platforms = listOf("desktop"), title = "$id (test)", group = group,
        url = "https://huggingface.co/x/$file", host = "huggingface.co", fileName = file, size = 1, sha256 = "0".repeat(64),
        revision = "r", origin = "o",
    )

    private class FakeStorage(val installed: MutableSet<String>) : ModelStorage {
        override fun isInstalled(item: LockedItem) = item.id in installed
        override fun pathOf(item: LockedItem) = "/models/" + item.fileName
        override fun commit(item: LockedItem, tempPath: String) {}
        override fun delete(item: LockedItem) {}
        override fun deleteTemp(tempPath: String) {}
        override fun importedUnverifiedFiles() = emptyList<String>()
        override fun keepUnverified(tempPath: String, fileName: String) {}
        override suspend fun pickAndImport(): ImportOutcome = ImportOutcome.Cancelled
    }

    @Test
    fun desktopModelChoice() {
        val registry = ModelRegistry(
            ModelLock(
                schemaVersion = 1,
                items = listOf(
                    item("llm-gemma4-e4b-gguf", "llm", "llm-desktop-gemma", "gemma.gguf"),
                    item(DesktopLlm.PREFERRED, "llm", "llm-desktop-qwen", "qwen.gguf"),
                    item("bin-llama-server-vulkan", "binary", "bin-llama", "llama.zip"),
                ),
            ),
        )
        val installed = mutableSetOf<String>()
        val llm = DesktopLlm(FakeStorage(installed), Files.createTempDirectory("bin").toFile())
        // No model, no server: stay on the mock engine.
        assertNull(llm.pick(registry, null))
        installed += "llm-gemma4-e4b-gguf"
        assertNull(llm.pick(registry, null), "a model without llama-server is not enough")
        installed += "bin-llama-server-vulkan"
        assertEquals("llm-gemma4-e4b-gguf", (llm.pick(registry, null) as LlamaServerEngine).model.id)
        installed += DesktopLlm.PREFERRED
        val qwen = llm.pick(registry, null) as LlamaServerEngine
        assertEquals(DesktopLlm.PREFERRED, qwen.model.id, "Qwen is the default")
        assertSame(qwen, llm.pick(registry, null), "the running engine is reused")
        assertEquals("llm-gemma4-e4b-gguf", (llm.pick(registry, "llm-gemma4-e4b-gguf") as LlamaServerEngine).model.id, "Settings choice wins")
        installed -= "llm-gemma4-e4b-gguf"
        assertNotNull(llm.pick(registry, "llm-gemma4-e4b-gguf"), "a deleted choice falls back to what is installed")
        llm.close()
    }
}
