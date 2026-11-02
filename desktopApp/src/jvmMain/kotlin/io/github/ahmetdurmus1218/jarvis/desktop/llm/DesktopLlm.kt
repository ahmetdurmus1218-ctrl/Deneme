package io.github.ahmetdurmus1218.jarvis.desktop.llm

import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
import java.io.File

/**
 * Chooses the desktop model: the one picked in Settings, else Qwen 3.5 9B, else any installed GGUF.
 * A real engine needs both a verified GGUF and the verified llama.cpp (Vulkan) zip; otherwise the
 * app keeps using the mock engine and says so.
 */
class DesktopLlm(private val storage: ModelStorage, private val binDir: File) : AutoCloseable {
    private var cached: LlamaServerEngine? = null

    @Synchronized
    fun pick(registry: ModelRegistry, chosenId: String?): LlmEngine? {
        val items = registry.forPlatform(Platform.DESKTOP)
        val server = items.firstOrNull { it.kind == "binary" && it.group == "bin-llama" && storage.isInstalled(it) } ?: return null
        val models = items.filter { it.kind == "llm" && it.fileName.endsWith(".gguf") && storage.isInstalled(it) }
        if (models.isEmpty()) return null
        val choice = models.firstOrNull { it.id == chosenId }
            ?: models.firstOrNull { it.id == PREFERRED }
            ?: models.first()
        val current = cached
        if (current?.model?.id != choice.id || current.serverZipName != server.fileName) {
            current?.close()
            cached = LlamaServerEngine(choice, storage.pathOf(choice), File(storage.pathOf(server)), binDir)
        }
        return cached
    }

    @Synchronized
    override fun close() {
        cached?.close()
        cached = null
    }

    companion object {
        const val PREFERRED = "llm-qwen35-9b-q4km"
    }
}
