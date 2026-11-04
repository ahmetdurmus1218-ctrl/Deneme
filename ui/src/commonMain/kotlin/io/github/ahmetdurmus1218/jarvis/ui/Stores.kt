package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.netlog.NetworkEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Settings(
    /** Locked LLM item id chosen by the user; null = recommended. */
    val llmId: String? = null,
    val voiceEnabled: Boolean = true,
    /** Hands-free: after answering a spoken question, Rick listens again by itself. */
    val conversationMode: Boolean = false,
    val speechRate: Float = 1.0f,
    /** The chosen voice (see SpeechOutput.voices); null = Rick's own voice. */
    val voiceId: String? = null,
    /** Old switch (before 1.1), no longer used; kept so saved settings still load. */
    val webSearch: Boolean = false,
    /**
     * When the offline library has no answer, offer to search the Turkish Wikipedia online. Rick only
     * asks; each search still needs the user's yes plus the platform approval (D38).
     */
    val webOffer: Boolean = true,
    /** Blocks every network path (gate/kapi), including model downloads. */
    val fullOffline: Boolean = false,
    val memoryEnabled: Boolean = true,
    val wizardDone: Boolean = false,
    /** How Rick addresses the user and labels the face lock ("AHMET · TANINDI"); empty = "efendim". */
    val userName: String = "",
    val effects: io.github.ahmetdurmus1218.jarvis.core.vision.EffectSettings = io.github.ahmetdurmus1218.jarvis.core.vision.EffectSettings(),
    /** Minutes without use before sleep mode (0 = never). */
    val sleepAfterMin: Int = 5,
    /** "alkis+soz": two claps then "uyan Rick"; "alkis": claps alone. */
    val wakeMode: String = "alkis+soz",
    /** How far above the room's noise a clap must jump (calibrated in Settings). */
    val clapThreshold: Float = 8f,
    /** The user's secret clap rhythm (gaps in ms); null = a plain double clap. */
    val clapRhythm: List<Long>? = null,
    /** 1 = Mark 1 (the clean stage), 2 = Mark 2 (dense HUD). */
    val hudMark: Int = 1,
    /** City for the offline sunrise/sunset on Mark 2. */
    val city: String = "İstanbul",
    /** Theme id (see Themes). */
    val theme: String = "jarvis",
    /** Text size (0.85 … 1.4). */
    val fontScale: Float = 1f,
    /** Calmer animations. */
    val reduceMotion: Boolean = false,
    /** Rick shown as a living face (true) or as the HUD core. */
    val face: Boolean = true,
    /** Hand control of the whole app with the camera (☰ → El ile yönet): swipe pages, click in the air. */
    val handNav: Boolean = false,
    /** "El ile yönet" shows the camera over the whole screen behind Rick (false: a small corner window). */
    val handNavFull: Boolean = true,
    /** Apps the hands may drive while Rick is in the background (never banks, payment, messengers: see HandApps). */
    val handApps: Set<String> = io.github.ahmetdurmus1218.jarvis.core.device.HandApps.DEFAULT,
    /** Rick's look: spark, eyes, orb, ring, particles (Settings → Görünüm). */
    val look: String = "spark",
    /** The floating Rick also watches the hands: swipe for the next song, ✋ to pause, 🤏 turn for volume. */
    val bubbleHands: Boolean = false,
    /** The floating Rick over other apps (Android), started from Settings. */
    val bubble: Boolean = false,
    /** While the floating Rick sleeps, two claps wake it (microphone stays on, on the device only). */
    val bubbleClapWake: Boolean = true,
    /** Learning from corrections / likes and reading while idle (all on the device). */
    val selfLearning: Boolean = true,
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class SettingsStore(private val files: AppFiles) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    private fun load(): Settings = files.read(FILE)?.let { runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        files.write(FILE, json.encodeToString(Settings.serializer(), next))
    }

    private companion object { const val FILE = "settings.json" }
}

/** Append-only network log (JSON lines). Every network event is written here (section 3.3). */
class NetworkLogStore(private val files: AppFiles) {
    private val _events = MutableStateFlow(load())
    val events: StateFlow<List<NetworkEvent>> = _events.asStateFlow()

    private fun load(): List<NetworkEvent> = files.read(FILE).orEmpty().lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { runCatching { json.decodeFromString(NetworkEvent.serializer(), it) }.getOrNull() }
        .toList()

    fun record(event: NetworkEvent) {
        files.append(FILE, json.encodeToString(NetworkEvent.serializer(), event))
        _events.value = _events.value + event
    }

    private companion object { const val FILE = "network-log.jsonl" }
}
