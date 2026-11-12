package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import kotlinx.coroutines.flow.StateFlow

/** Everything the shared UI needs from the host platform (Android "Kale" or Windows desktop). */
interface PlatformBridge {
    /** Offline Wikipedia (ZIM) access; null where the platform has none. */
    val zim: io.github.ahmetdurmus1218.jarvis.core.knowledge.ZimBackend? get() = null

    /** Live camera with hand and face tracking (the phone); null where there is none. */
    val camera: VisionCamera? get() = null

    /** Encrypts the app's files at rest (Android Keystore / Windows DPAPI-protected key); null = plain. */
    val cipher: FileCipher? get() = null

    /** The PC's outbound firewall rules (Windows, installed app); null elsewhere. */
    val firewall: FirewallControl? get() = null

    /** Battery level in percent, or null when the device does not say (a desktop PC). */
    fun batteryPercent(): Int? = null

    /** Timers and alarms the app rings itself (the PC; the phone uses its clock app): the sentence to say. */
    val reminders: kotlinx.coroutines.flow.Flow<String> get() = kotlinx.coroutines.flow.emptyFlow()

    /** Memory used by Rick in MB (Mark 2's session panel), null if unknown. */
    fun memoryUsedMb(): Long? = null

    /** Keeps the screen from turning off (sleep mode waits for claps). */
    fun keepScreenOn(on: Boolean) {}

    /** Raw 16 kHz microphone samples for the clap wake-up (sleep mode); null if the device has none. */
    val micStream: MicStream? get() = null

    /** True while some app plays music (a song, a video), so a hand swipe means "next song". */
    fun mediaPlaying(): Boolean = false

    /** A tap at ([x], [y]) in window pixels inside Rick's own window (the air click); false if not possible here. */
    fun injectTap(x: Float, y: Float): Boolean = false

    /** Rick floating over other apps (Android); null where there is none. */
    val floating: FloatingRick? get() = null

    /** A screenshot of this screen as JPEG (the PC), for "ekranda ne var / ekranı oku". */
    suspend fun screenshotJpeg(): ByteArray? = null

    /** An image file picked by the user, as JPEG; null if cancelled or unsupported. */
    suspend fun pickImageJpeg(): ByteArray? = null

    /** Opens the file picker and returns the text of a PDF / TXT / MD file (read on the device). */
    suspend fun pickDocument(): DocumentPick = DocumentPick.Failed("Bu cihazda belge ekleme desteklenmiyor")
    val platform: Platform
    val appVersion: String
    fun deviceInfo(): DeviceInfo
    val files: AppFiles
    val speechInput: SpeechInput?
    val speechOutput: SpeechOutput?
    val approver: Approver
    val downloader: Downloader
    val models: ModelStorage

    /** One approved Wikipedia web search through the gate; null when this device has no gate. */
    val web: WebGate? get() = null

    /** Executes a quick command on the device. Returns an error message, or null on success. */
    suspend fun execute(command: QuickCommand): String?
}

data class DeviceInfo(
    val label: String,
    val totalRamGb: Double,
    val freeStorageBytes: Long,
    val notes: List<String> = emptyList(),
)

/** Small private text files in app storage (settings, network log). */
interface AppFiles {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun append(name: String, line: String)
}

/** Rick's outbound block rules in the Windows firewall. */
interface FirewallControl {
    fun status(): String
    fun installed(): Boolean
    /** One UAC prompt. */
    suspend fun install(): Boolean
    suspend fun remove(): Boolean
}

/** The microphone as a stream of 16 kHz mono samples (−1..1), only while started; nothing is stored. */
/**
 * The small living face over other apps: tap to talk, hold to open Rick, drag to move. While it
 * sleeps, two claps wake it (if allowed). Runs as a foreground service with a notification.
 */
interface FloatingRick {
    /** True when the system lets Rick draw over other apps. */
    fun allowed(): Boolean
    /** Opens the system screen where the user allows it. */
    fun askPermission()
    /**
     * Starts it (asks for the microphone first, and for the camera when [hands]: it then also
     * watches for hand gestures); a reason when it could not.
     */
    suspend fun start(hands: Boolean = false): String?
    fun stop()
    val running: kotlinx.coroutines.flow.StateFlow<Boolean>

    /** Hand control of other apps (Android accessibility) is switched on in the system. */
    fun appControlOn(): Boolean = false
    /** Opens the system screen where it is switched on. */
    fun openAppControlSettings() {}
    /** Installed apps the user may pick for hand control (package to name); banks, messengers etc. are never listed. */
    fun appChoices(): List<Pair<String, String>> = emptyList()
}

/**
 * The one raw microphone stream is shared: a screen that needs it for itself (sleep, clap calibration)
 * takes it here first, and hands-free standby steps aside at once and comes back afterwards.
 */
object MicUse {
    private var holders = 0
    private val watchers = ArrayList<() -> Unit>()
    val taken: Boolean get() = holders > 0

    fun take() {
        holders++
        if (holders == 1) watchers.toList().forEach { it() }
    }

    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) watchers.toList().forEach { it() }
    }

    /** Calls [onChange] (on the caller's thread) whenever the microphone is taken or given back; returns the undo. */
    fun watch(onChange: () -> Unit): () -> Unit {
        watchers += onChange
        return { watchers -= onChange }
    }
}

interface MicStream {
    /**
     * Whether the microphone may be used without asking the user first. Hands-free standby never
     * brings up a permission question by itself: it waits until the user has used the microphone once.
     */
    val permitted: Boolean get() = true

    /** Starts delivering samples on a background thread; false if the microphone cannot be opened. */
    suspend fun start(onSamples: (FloatArray, Int) -> Unit): Boolean
    fun stop()
}

/** How every "nothing was said" message of a recognizer starts (hands-free listening stays quiet about it). */
const val NOTHING_HEARD = "Bir şey duyamadım"

interface SpeechInput {
    /** Human-readable engine name, e.g. "Android cihaz içi tanıma (tr-TR)". */
    val engineName: String
    fun isAvailable(): Boolean
    fun start(listener: Listener)
    fun stop()
    fun cancel()

    /** Whether the recognizer's Turkish on-device language pack is installed. */
    suspend fun languagePack(): LanguagePack = LanguagePack.UNKNOWN

    /**
     * Asks the operating system to download its own Turkish on-device language pack. Only called after
     * the user's approval; the download is done by the system, never by this app.
     */
    suspend fun downloadLanguagePack(): Boolean = false

    enum class LanguagePack { READY, DOWNLOADABLE, UNSUPPORTED, UNKNOWN }

    /**
     * Lock-file group of Rick's own speech model (Whisper) when it could be used but is not installed
     * yet; the chat screen then offers a one-tap download. Null when installed or not applicable.
     */
    val ownModelGroup: String? get() = null

    /** Loads the recognizer in the background so the next start answers quickly (sleep → wake word). */
    fun warmUp() {}

    /**
     * Whether [transcribe] works right now (Rick's own recognizer is installed). Hands-free standby
     * needs it: the app listens itself and only hands over what was said.
     */
    val canTranscribe: Boolean get() = false

    /** Turns 16 kHz mono samples (-1..1) into text on the device; null when not possible. */
    suspend fun transcribe(samples: FloatArray): String? = null

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onLevel(rms: Float) {}
    }
}

interface SpeechOutput {
    val engineName: String
    /** Speaks [text] and suspends until finished (or stopped). */
    suspend fun speak(text: String, rate: Float)
    fun stop()

    /**
     * Why nothing can be heard right now (for example: no Turkish voice works offline on this phone),
     * or null when speaking works. The chat shows it instead of staying silent without a word.
     */
    val silentReason: String? get() = null

    /** Lock-file group of the assistant's own voice (Piper) while it is not installed; null otherwise. */
    val ownModelGroup: String? get() = null

    /** Something the voice did by itself that the user should know (e.g. "medya sesi kapalıydı, açtım"), once. */
    fun takeNotice(): String? = null

    /** Voices the user can choose in Settings (empty: only the one in use). */
    fun voices(): List<VoiceChoice> = emptyList()

    /** Speaks with [id] from [voices] from now on; null = Rick's default voice. */
    fun choose(id: String?) {}

    /** Opens the device's own voice settings, where more (offline) voices can be added; false: not here. */
    fun openVoiceSettings(): Boolean = false
}

enum class VoiceGender(val label: String) {
    KADIN("Kadın"), ERKEK("Erkek");

    companion object {
        /** From a voice's name / features, when the engine says it ("female", "tr-tr-f01", "#male_2"); null otherwise. */
        fun guess(text: String): VoiceGender? {
            val t = text.lowercase()
            return when {
                Regex("female|kadin|kadın|woman|(?:^|[-_#\\s])f\\d{1,2}(?:$|[-_\\s])").containsMatchIn(t) -> KADIN
                Regex("(?:^|[^e])male|erkek|\\bman\\b|(?:^|[-_#\\s])m\\d{1,2}(?:$|[-_\\s])").containsMatchIn(t) -> ERKEK
                else -> null
            }
        }
    }
}

/** A voice Rick can speak with. [gender] is null when the voice does not say (then the user listens and picks). */
data class VoiceChoice(val id: String, val label: String, val gender: VoiceGender?, val note: String = "")

enum class ApprovalMethod(val label: String) {
    BIOMETRIC("biyometrik / PIN"),
    DIALOG("onay penceresi"),
    UAC("UAC"),
}

sealed interface ApprovalResult {
    data class Approved(val method: ApprovalMethod) : ApprovalResult
    data class Rejected(val reason: String) : ApprovalResult
}

/** Strong user confirmation: BiometricPrompt on Android, confirmation (UAC in v0.5) on desktop. */
/**
 * The way out for one approved web search (Turkish Wikipedia only): the Android gate app or the
 * PC's rick-kapi. Rick itself never opens a connection.
 */
interface WebGate {
    /** Fetches [url], which must pass OnlineWikipedia.allowed; the gate checks it again. */
    suspend fun fetch(url: String): WebFetch
    /** Called after every fetch: closes what the approval opened (the PC's firewall gate). */
    suspend fun done() {}
}

sealed interface WebFetch {
    data class Ok(val body: String, val host: String, val bytes: Long) : WebFetch
    data class Failed(val message: String) : WebFetch
}

interface Approver {
    suspend fun confirm(title: String, subtitle: String): ApprovalResult
}

sealed interface DownloadState {
    data object Unavailable : DownloadState
    data class Ready(val label: String) : DownloadState
}

data class DownloadProgress(val bytes: Long, val total: Long)

sealed interface DownloadOutcome {
    /** File stored in app storage. [sha256] was computed by the app itself after receiving it. */
    data class Done(val path: String, val bytes: Long, val sha256: String, val host: String) : DownloadOutcome
    data class Failed(val message: String, val bytes: Long = 0) : DownloadOutcome
}

/** The only path to the internet: the "Kapı" APK on Android, the rick-kapi launcher on desktop. */
interface Downloader {
    val state: StateFlow<DownloadState>
    fun refresh()
    suspend fun download(item: LockedItem, onProgress: (DownloadProgress) -> Unit): DownloadOutcome
    fun cancel()
    /** One approved download session has ended (the PC closes its firewall gate); [installedPaths] = newly installed files. */
    suspend fun endSession(installedPaths: List<String>) {}
}

sealed interface ImportOutcome {
    data class Imported(val tempPath: String, val fileName: String, val sha256: String, val bytes: Long) : ImportOutcome
    data object Cancelled : ImportOutcome
    data class Failed(val message: String) : ImportOutcome
}

/** Model files in app storage. A file counts as installed only after its hash matched the lock. */
interface ModelStorage {
    fun isInstalled(item: LockedItem): Boolean
    fun pathOf(item: LockedItem): String
    /** Moves a downloaded/imported file (already hash-checked by the caller) into place and records the verified hash. */
    fun commit(item: LockedItem, tempPath: String)
    fun delete(item: LockedItem)
    fun deleteTemp(tempPath: String)
    fun importedUnverifiedFiles(): List<String>
    /** Keeps a file that matched no lock entry, after the user's double confirmation. */
    fun keepUnverified(tempPath: String, fileName: String)
    /** Opens the system file picker and copies the chosen file into a temp location while hashing it. */
    suspend fun pickAndImport(): ImportOutcome

    /**
     * Looks for [items] already on this device — an older installation, the Downloads folder, other
     * programs' model folders, folders the user picked — by file name and size. A candidate is
     * taken over only if its SHA-256 matches the lock ([onCandidate] fires before that check).
     * Nothing is downloaded. Returns the ids now installed.
     */
    suspend fun adoptExisting(items: List<LockedItem>, onCandidate: (LockedItem) -> Unit = {}): Set<String> = emptySet()

    /** Lets the user add a folder to search (remembered); false when cancelled or not supported. */
    suspend fun pickSearchFolder(): Boolean = false
}

sealed interface DocumentPick {
    data class Picked(val name: String, val text: String) : DocumentPick
    data object Cancelled : DocumentPick
    data class Failed(val message: String) : DocumentPick
}

/** Largest document accepted (PDFs of books are fine; this only stops accidents). */
const val MAX_DOCUMENT_BYTES = 60L * 1024 * 1024

/** What the camera saw in one frame: hands (21 landmarks each) and faces, in 0..1 image coordinates. */
data class VisionFrame(
    val hands: List<io.github.ahmetdurmus1218.jarvis.core.vision.Hand>,
    val faces: List<io.github.ahmetdurmus1218.jarvis.core.vision.FaceBox>,
    /** Size of the analysed image, to map the 0..1 coordinates onto the preview. */
    val width: Int,
    val height: Int,
)

/** The phone camera: live preview plus on-device hand/face tracking (MediaPipe models from the lock). */
interface VisionCamera {
    /** True when the hand and face tracking models are installed and verified. */
    fun trackingReady(): Boolean
    /** Lock-file group to download for tracking. */
    val trackingGroup: String

    /** The live preview; [onFrame] is called from a background thread for every analysed frame. */
    @androidx.compose.runtime.Composable
    fun Preview(modifier: androidx.compose.ui.Modifier, frontCamera: Boolean, onFrame: (VisionFrame) -> Unit)

    /** The latest frame as JPEG (what the user sees), for "bu ne?" / "oku". */
    suspend fun snapshotJpeg(): ByteArray?
}
