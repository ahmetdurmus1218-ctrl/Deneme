package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import kotlinx.coroutines.flow.StateFlow

/** Everything the shared UI needs from the host platform (Android "Kale" or Windows desktop). */
interface PlatformBridge {
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

interface SpeechInput {
    /** Human-readable engine name, e.g. "Android cihaz içi tanıma (tr-TR)". */
    val engineName: String
    fun isAvailable(): Boolean
    fun start(listener: Listener)
    fun stop()
    fun cancel()

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
