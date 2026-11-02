package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.formatBytes
import io.github.ahmetdurmus1218.jarvis.core.net.DownloadPolicy
import io.github.ahmetdurmus1218.jarvis.core.netlog.NetworkEvent
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the approval screen shows (section 3.3.1). */
data class ApprovalRequest(
    val title: String,
    val action: String,
    val hosts: List<String>,
    val totalBytes: Long?,
    val reason: String,
    val lines: List<ApprovalLine>,
    /** For web search: the full text that would leave the device. */
    val outgoingText: String? = null,
    /** File not in models.lock.json: red "Doğrulanamadı" warning; needs two approvals. */
    val unverified: Boolean = false,
    /** 1 or 2 when [unverified]. */
    val step: Int = 1,
)

data class ApprovalLine(val name: String, val size: Long?, val sha256: String?)

sealed interface ItemStatus {
    data object Waiting : ItemStatus
    data class Downloading(val bytes: Long, val total: Long) : ItemStatus
    data object Verifying : ItemStatus
    data object Done : ItemStatus
    data class Failed(val message: String, val hashMismatch: Boolean = false) : ItemStatus
}

/**
 * Downloads and verifies locked items: policy check → user approval (one approval per request) →
 * download through the gate → SHA-256 recomputed by the app → commit or delete. Every network
 * request is written to the network log.
 */
class ModelInstaller(
    private val bridge: PlatformBridge,
    private val registry: ModelRegistry,
    private val settings: SettingsStore,
    private val netLog: NetworkLogStore,
    private val now: NowProvider = NowProvider.System,
) {
    private val _approval = MutableStateFlow<ApprovalRequest?>(null)
    val approval: StateFlow<ApprovalRequest?> = _approval.asStateFlow()
    private var approvalAnswer: CompletableDeferred<Boolean>? = null

    private val _status = MutableStateFlow<Map<String, ItemStatus>>(emptyMap())
    val status: StateFlow<Map<String, ItemStatus>> = _status.asStateFlow()

    private val _installedVersion = MutableStateFlow(0)
    /** Bumps whenever installed files change, so screens can refresh. */
    val installedVersion: StateFlow<Int> = _installedVersion.asStateFlow()

    /** Called by the approval screen. */
    fun answerApproval(approved: Boolean) {
        approvalAnswer?.complete(approved)
    }

    private suspend fun askOnScreen(req: ApprovalRequest): Boolean {
        val answer = CompletableDeferred<Boolean>()
        approvalAnswer = answer
        _approval.value = req
        return try {
            answer.await()
        } finally {
            _approval.value = null
            approvalAnswer = null
        }
    }

    /** Why the last approval was not given (shown to the user). */
    var lastRejection: String? = null
        private set

    /** Shows the approval screen, then asks for strong authentication. Returns the method or null. */
    suspend fun requestApproval(req: ApprovalRequest): ApprovalMethod? {
        if (!askOnScreen(req)) {
            lastRejection = "Onay ekranında reddedildi"
            return null
        }
        return when (val r = bridge.approver.confirm(req.title, req.action)) {
            is ApprovalResult.Approved -> {
                lastRejection = null
                r.method
            }
            is ApprovalResult.Rejected -> {
                lastRejection = r.reason
                null
            }
        }
    }

    private fun rejectionText() = "Onay verilmedi" + (lastRejection?.let { ": $it" } ?: "")

    fun setStatus(id: String, s: ItemStatus) {
        _status.value = _status.value + (id to s)
    }

    /**
     * Installs [items] after ONE approval covering exactly these files. Returns true if all succeeded.
     */
    suspend fun install(items: List<LockedItem>, reason: String): Boolean {
        val pending = items.filterNot { bridge.models.isInstalled(it) }
        if (pending.isEmpty()) return true
        if (settings.state.value.fullOffline) {
            pending.forEach { setStatus(it.id, ItemStatus.Failed("Tam offline mod açık")) }
            return false
        }
        for (item in pending) {
            val verdict = DownloadPolicy.check(item, bridge.platform)
            if (verdict is DownloadPolicy.Verdict.Denied) {
                setStatus(item.id, ItemStatus.Failed(verdict.reason))
                return false
            }
        }
        if (bridge.downloader.state.value is DownloadState.Unavailable) {
            pending.forEach { setStatus(it.id, ItemStatus.Failed(gateMissingText())) }
            return false
        }
        val req = ApprovalRequest(
            title = "İnternet erişimi onayı",
            action = "${pending.size} dosya indirilecek",
            hosts = pending.map { it.host }.distinct(),
            totalBytes = pending.sumOf { it.size },
            reason = reason,
            lines = pending.map { ApprovalLine(it.fileName, it.size, it.sha256) },
        )
        val method = requestApproval(req)
        if (method == null) {
            pending.forEach { setStatus(it.id, ItemStatus.Failed(rejectionText())) }
            netLog.record(NetworkEvent(now.now().epochMillis, req.hosts.joinToString(), 0, req.action, "-", "reddedildi"))
            return false
        }
        pending.forEach { setStatus(it.id, ItemStatus.Waiting) }
        var allOk = true
        for (item in pending) {
            if (!downloadOne(item, method)) allOk = false
        }
        _installedVersion.value++
        return allOk
    }

    private suspend fun downloadOne(item: LockedItem, method: ApprovalMethod): Boolean {
        setStatus(item.id, ItemStatus.Downloading(0, item.size))
        val outcome = bridge.downloader.download(item) { p -> setStatus(item.id, ItemStatus.Downloading(p.bytes, p.total)) }
        return when (outcome) {
            is DownloadOutcome.Failed -> {
                setStatus(item.id, ItemStatus.Failed(outcome.message))
                netLog.record(NetworkEvent(now.now().epochMillis, item.host, outcome.bytes, item.fileName, method.label, "hata: ${outcome.message}"))
                false
            }
            is DownloadOutcome.Done -> {
                setStatus(item.id, ItemStatus.Verifying)
                val ok = outcome.sha256.equals(item.sha256, ignoreCase = true) && outcome.bytes == item.size
                if (ok) {
                    bridge.models.commit(item, outcome.path)
                    setStatus(item.id, ItemStatus.Done)
                } else {
                    bridge.models.deleteTemp(outcome.path)
                    setStatus(item.id, ItemStatus.Failed("SHA-256 uyuşmadı, dosya silindi", hashMismatch = true))
                }
                netLog.record(
                    NetworkEvent(now.now().epochMillis, outcome.host, outcome.bytes, item.fileName, method.label, if (ok) "tamam" else "hash uyuşmadı"),
                )
                ok
            }
        }
    }

    sealed interface ImportResult {
        data class Installed(val item: LockedItem) : ImportResult
        data class KeptUnverified(val fileName: String) : ImportResult
        data class Discarded(val reason: String) : ImportResult
        data object Cancelled : ImportResult
    }

    /** "Dosyadan yükle": hash is checked against the lock; unknown files need two approvals. */
    suspend fun importFromFile(): ImportResult {
        return when (val r = bridge.models.pickAndImport()) {
            ImportOutcome.Cancelled -> ImportResult.Cancelled
            is ImportOutcome.Failed -> ImportResult.Discarded(r.message)
            is ImportOutcome.Imported -> {
                val match = registry.bySha256(r.sha256)?.takeIf { bridge.platform.key in it.platforms }
                if (match != null && match.size == r.bytes) {
                    bridge.models.commit(match, r.tempPath)
                    setStatus(match.id, ItemStatus.Done)
                    _installedVersion.value++
                    ImportResult.Installed(match)
                } else {
                    val base = ApprovalRequest(
                        title = "Doğrulanamadı",
                        action = "Kilit dosyasında olmayan bir dosya eklenecek: ${r.fileName}",
                        hosts = emptyList(),
                        totalBytes = r.bytes,
                        reason = "Bu dosyanın SHA-256 özeti models.lock.json içindeki hiçbir kayıtla eşleşmiyor. Kaynağından emin değilseniz reddedin.",
                        lines = listOf(ApprovalLine(r.fileName, r.bytes, r.sha256)),
                        unverified = true,
                    )
                    val first = requestApproval(base)
                    val second = first?.let { requestApproval(base.copy(step = 2, title = "Doğrulanamadı — ikinci onay")) }
                    if (first != null && second != null) {
                        bridge.models.keepUnverified(r.tempPath, r.fileName)
                        _installedVersion.value++
                        ImportResult.KeptUnverified(r.fileName)
                    } else {
                        bridge.models.deleteTemp(r.tempPath)
                        ImportResult.Discarded(rejectionText() + ". Dosya silindi.")
                    }
                }
            }
        }
    }

    fun delete(item: LockedItem) {
        bridge.models.delete(item)
        _status.value = _status.value - item.id
        _installedVersion.value++
    }

    fun gateMissingText(): String = when (bridge.platform) {
        io.github.ahmetdurmus1218.jarvis.core.models.Platform.ANDROID ->
            "Kapı yüklü değil. İndirmek için jarvis-kapi.apk kurun ya da dosyaları PC'den kopyalayıp 'Dosyadan yükle'yi kullanın."
        io.github.ahmetdurmus1218.jarvis.core.models.Platform.DESKTOP -> "İndirme bileşeni kullanılamıyor."
    }

    fun describe(items: List<LockedItem>): String = "${items.size} dosya, toplam ${formatBytes(items.sumOf { it.size })}"
}
