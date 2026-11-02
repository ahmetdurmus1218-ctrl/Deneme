package io.github.ahmetdurmus1218.jarvis.core.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LockedItem(
    val id: String,
    /** llm | stt | tts | zim | embedding | binary */
    val kind: String,
    /** android | desktop */
    val platforms: List<String>,
    val title: String,
    /** Items that must be installed together (e.g. Whisper encoder + decoder + tokens). */
    val group: String,
    val url: String,
    val host: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val revision: String,
    val origin: String,
    val license: String? = null,
    val minRamGb: Int? = null,
    val minVramGb: Int? = null,
)

@Serializable
data class ModelLock(
    val schemaVersion: Int,
    val generatedAt: String? = null,
    val generator: String? = null,
    val items: List<LockedItem> = emptyList(),
)

enum class Platform(val key: String) { ANDROID("android"), DESKTOP("desktop") }

/** The model registry: models.lock.json compiled into the app. */
class ModelRegistry(val lock: ModelLock) {

    fun forPlatform(p: Platform): List<LockedItem> = lock.items.filter { p.key in it.platforms }

    fun byId(id: String): LockedItem? = lock.items.firstOrNull { it.id == id }

    fun bySha256(sha: String): LockedItem? = lock.items.firstOrNull { it.sha256.equals(sha, ignoreCase = true) }

    fun group(group: String): List<LockedItem> = lock.items.filter { it.group == group }

    /**
     * Recommended first-run bundle (LLM + STT + TTS + Wikipedia).
     * Android: Gemma 4 E4B when the device has >= 12 GB RAM, otherwise E2B.
     */
    fun recommendedBundle(p: Platform, ramGb: Double): List<LockedItem> {
        val items = forPlatform(p)
        val llmGroup = when (p) {
            Platform.ANDROID -> if (ramGb >= 11.5) "llm-android-e4b" else "llm-android-e2b"
            Platform.DESKTOP -> "llm-desktop-qwen"
        }
        val groups = buildList {
            add(llmGroup)
            add("stt-whisper-small")
            add("tts-piper-tr")
            add("wiki-tr")
            if (p == Platform.DESKTOP) { add("bin-llama"); add("bin-kiwix") }
        }
        return groups.flatMap { g -> items.filter { it.group == g } }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ModelRegistry = ModelRegistry(json.decodeFromString(ModelLock.serializer(), text))

        /** The lock file this build was compiled with. */
        val bundled: ModelRegistry by lazy { parse(LOCKED_MODELS_JSON) }
    }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1) return "${(kotlin.math.round(gb * 10) / 10).toString().replace('.', ',')} GB"
    val mb = bytes / 1_000_000.0
    if (mb >= 1) return "${kotlin.math.round(mb).toLong()} MB"
    return "${(bytes / 1000).coerceAtLeast(1)} KB"
}
