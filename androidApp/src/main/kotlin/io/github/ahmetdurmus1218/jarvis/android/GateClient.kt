package io.github.ahmetdurmus1218.jarvis.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.ui.DownloadOutcome
import io.github.ahmetdurmus1218.jarvis.ui.DownloadProgress
import io.github.ahmetdurmus1218.jarvis.ui.DownloadState
import io.github.ahmetdurmus1218.jarvis.ui.Downloader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume

/**
 * Kale's side of the gate: binds Kapı's signature-protected service, hands it a writable file
 * descriptor of the destination file and receives progress. Kale itself has no network access.
 * After the gate reports success, Kale recomputes the SHA-256 on its own copy.
 */
class GateClient(private val context: Context, private val storage: AndroidModelStorage) : Downloader, io.github.ahmetdurmus1218.jarvis.ui.WebGate {
    private companion object {
        const val BIND_TIMEOUT_MS = 15_000L
        const val QUERY_TIMEOUT_MS = 30_000L
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Unavailable)
    override val state: StateFlow<DownloadState> = _state
    @Volatile private var activeService: Messenger? = null
    @Volatile private var activeItem: String? = null
    @Volatile private var currentResult: CompletableDeferred<DownloadOutcome>? = null

    override fun refresh() {
        val pm = context.packageManager
        _state.value = try {
            val info = pm.getPackageInfo(GateProtocol.GATE_PACKAGE, 0)
            if (pm.checkSignatures(context.packageName, GateProtocol.GATE_PACKAGE) != PackageManager.SIGNATURE_MATCH) {
                DownloadState.Unavailable
            } else {
                DownloadState.Ready("Rick Kapı ${info.versionName}")
            }
        } catch (_: PackageManager.NameNotFoundException) {
            DownloadState.Unavailable
        }
    }

    private suspend fun bind(): Pair<Messenger, ServiceConnection>? = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (cont.isActive) cont.resume(service?.let { Messenger(it) to this })
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                activeService = null
                currentResult?.complete(DownloadOutcome.Failed("Kapı bağlantısı koptu"))
            }
            override fun onNullBinding(name: ComponentName?) { if (cont.isActive) cont.resume(null) }
        }
        val intent = Intent().setComponent(ComponentName(GateProtocol.GATE_PACKAGE, GateProtocol.GATE_SERVICE))
        val ok = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        } catch (_: SecurityException) {
            false
        }
        if (!ok) {
            runCatching { context.unbindService(conn) }
            if (cont.isActive) cont.resume(null)
        }
        cont.invokeOnCancellation { runCatching { context.unbindService(conn) } }
    }

    /** One transfer at a time: the shared fields below belong to the running transfer. */
    private val transferLock = Mutex()

    override suspend fun download(item: LockedItem, onProgress: (DownloadProgress) -> Unit): DownloadOutcome =
        transferLock.withLock { downloadLocked(item, onProgress) }

    private suspend fun downloadLocked(item: LockedItem, onProgress: (DownloadProgress) -> Unit): DownloadOutcome {
        refresh()
        if (_state.value !is DownloadState.Ready) return DownloadOutcome.Failed("Kapı yüklü değil")
        val dest: File = storage.partialFile(item)
        dest.parentFile?.mkdirs()
        // A gate that is installed but never answers (crashed, disabled) must not hang the wizard.
        val (service, conn) = withTimeoutOrNull(BIND_TIMEOUT_MS) { bind() }
            ?: return DownloadOutcome.Failed("Kapı servisine bağlanılamadı")
        activeService = service
        activeItem = item.id
        val result = CompletableDeferred<DownloadOutcome>()
        currentResult = result
        val main = Handler(Looper.getMainLooper())
        val replies = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val data = msg.data
                when (msg.what) {
                    GateProtocol.MSG_PROGRESS -> onProgress(DownloadProgress(data.getLong(GateProtocol.KEY_BYTES), data.getLong(GateProtocol.KEY_TOTAL)))
                    GateProtocol.MSG_DONE -> result.complete(
                        DownloadOutcome.Done(dest.absolutePath, data.getLong(GateProtocol.KEY_BYTES), "", data.getString(GateProtocol.KEY_HOST) ?: item.host),
                    )
                    GateProtocol.MSG_ERROR -> result.complete(
                        DownloadOutcome.Failed(data.getString(GateProtocol.KEY_MESSAGE) ?: "Bilinmeyen hata", data.getLong(GateProtocol.KEY_BYTES)),
                    )
                }
            }
        })
        val pfd = ParcelFileDescriptor.open(
            dest,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
        )
        try {
            val msg = Message.obtain(null, GateProtocol.MSG_DOWNLOAD).apply {
                replyTo = replies
                data = Bundle().apply {
                    putString(GateProtocol.KEY_ITEM_ID, item.id)
                    putString(GateProtocol.KEY_URL, item.url)
                    putString(GateProtocol.KEY_SHA256, item.sha256)
                    putLong(GateProtocol.KEY_SIZE, item.size)
                    putString(GateProtocol.KEY_FILE_NAME, item.fileName)
                    putLong(GateProtocol.KEY_OFFSET, dest.length())
                    putParcelable(GateProtocol.KEY_FD, pfd)
                }
            }
            service.send(msg)
            val outcome = result.await()
            if (outcome !is DownloadOutcome.Done) return outcome
            // Kale recomputes the hash itself; it never trusts the gate's value.
            if (!dest.isFile) return DownloadOutcome.Failed("İndirilen dosya bulunamadı; tekrar deneyin")
            val (sha, size) = withContext(Dispatchers.IO) { Hashing.sha256(dest) }
            return outcome.copy(sha256 = sha, bytes = size)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                cancel()
                throw e
            }
            return DownloadOutcome.Failed(e.message ?: "Kapı hatası")
        } finally {
            runCatching { pfd.close() }
            activeService = null
            activeItem = null
            currentResult = null
            main.post { runCatching { context.unbindService(conn) } }
        }
    }

    /**
     * One approved Wikipedia search through Kapı (Kale has no network permission). A gate older than
     * 1.1 does not know the request and stays silent: that ends in a timeout with an update hint.
     */
    override suspend fun fetch(url: String): io.github.ahmetdurmus1218.jarvis.ui.WebFetch = run {
        val failed = io.github.ahmetdurmus1218.jarvis.ui.WebFetch::Failed
        if (!io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.allowed(url)) return@run failed("İzin verilmeyen web isteği")
        refresh()
        if (_state.value !is DownloadState.Ready) return@run failed("Rick Kapı yüklü değil (rick-kapi.apk)")
        val (service, conn) = withTimeoutOrNull(BIND_TIMEOUT_MS) { bind() } ?: return@run failed("Kapı servisine bağlanılamadı")
        val result = CompletableDeferred<io.github.ahmetdurmus1218.jarvis.ui.WebFetch>()
        val replies = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val data = msg.data
                when (msg.what) {
                    GateProtocol.MSG_QUERY_DONE -> result.complete(
                        io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Ok(
                            data.getString(GateProtocol.KEY_TEXT).orEmpty(),
                            data.getString(GateProtocol.KEY_HOST) ?: io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.HOST,
                            data.getLong(GateProtocol.KEY_BYTES),
                        ),
                    )
                    GateProtocol.MSG_ERROR -> result.complete(failed(data.getString(GateProtocol.KEY_MESSAGE) ?: "Bilinmeyen hata"))
                }
            }
        })
        try {
            service.send(Message.obtain(null, GateProtocol.MSG_QUERY).apply {
                replyTo = replies
                data = Bundle().apply { putString(GateProtocol.KEY_URL, url) }
            })
            withTimeoutOrNull(QUERY_TIMEOUT_MS) { result.await() }
                ?: failed("Kapı cevap vermedi. Rick Kapı'nın son sürümünü (rick-kapi.apk) kurun.")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed(e.message ?: "Kapı hatası")
        } finally {
            Handler(Looper.getMainLooper()).post { runCatching { context.unbindService(conn) } }
        }
    }

    override fun cancel() {
        val s = activeService ?: return
        runCatching {
            s.send(Message.obtain(null, GateProtocol.MSG_CANCEL).apply { data = Bundle().apply { putString(GateProtocol.KEY_ITEM_ID, activeItem) } })
        }
    }
}
