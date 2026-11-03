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

    /** Keeps the screen from turning off (sleep mode waits for claps). */
    fun keepScreenOn(on: Boolean) {}

    /** Raw 16 kHz microphone samples for the clap wake-up (sleep mode); null if the device has none. */
    val micStream: MicStream? get() = null

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

/** The microphone as a stream of 16 kHz mono samples (−1..1), only while started; nothing is stored. */
interface MicStream {
    /** Starts delivering samples on a background thread; false if the microphone cannot be opened. */
    suspend fun start(onSamples: (FloatArray, Int) -> Unit): Boolean
    fun stop()
}

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
     * Lock-file group of Jarvis's own speech model (Whisper) when it could be used but is not installed
     * yet; the chat screen then offers a one-tap download. Null when installed or not applicable.
     */
    val ownModelGroup: String? get() = null

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
}

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

/** The only path to the internet: the "Kapı" APK on Android, the jarvis-kapi launcher on desktop. */
interface Downloader {
    val state: StateFlow<DownloadState>
    fun refresh()
    suspend fun download(item: LockedItem, onProgress: (DownloadProgress) -> Unit): DownloadOutcome
    fun cancel()
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
