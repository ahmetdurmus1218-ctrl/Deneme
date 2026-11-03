package io.github.ahmetdurmus1218.jarvis.gate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import androidx.core.os.BundleCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bound service, protected by the signature permission GATE. Every message is additionally checked
 * to come from the Rick package signed with the same key. The service only acts on requests; it
 * never initiates network traffic by itself.
 */
class GateService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val downloader by lazy { GateDownloader() }
    private lateinit var messenger: Messenger
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(IncomingHandler(Looper.getMainLooper()))
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private val running = AtomicInteger(0)

    /**
     * Long downloads must survive the screen turning off or Rick going to the background, so while
     * a transfer runs the gate is a foreground service with a visible notification. Allowed because
     * the (visible) Rick app is bound to it; if Android refuses, the download still runs.
     */
    private fun enterForeground() {
        if (running.getAndIncrement() > 0) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Rick Kapı")
                .setContentText("Onayladığınız dosya indiriliyor…")
                .setOngoing(true)
                .build()
            startForegroundService(Intent(this, GateService::class.java))
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            GateLog(this).add("ön plan servisi açılamadı: ${e.javaClass.simpleName}")
        }
    }

    private fun leaveForeground() {
        if (running.decrementAndGet() > 0) return
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        cancelFlags.values.forEach { it.set(true) }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun callerAllowed(uid: Int): Boolean {
        val pm = packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return false
        return GateProtocol.KALE_PACKAGE in pkgs &&
            pm.checkSignatures(packageName, GateProtocol.KALE_PACKAGE) == PackageManager.SIGNATURE_MATCH
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo
            if (!callerAllowed(msg.sendingUid)) {
                reply(replyTo, GateProtocol.MSG_ERROR) { putString(GateProtocol.KEY_MESSAGE, "Yetkisiz istek") }
                return
            }
            val data = Bundle(msg.data).apply { classLoader = ParcelFileDescriptor::class.java.classLoader }
            when (msg.what) {
                GateProtocol.MSG_PING -> reply(replyTo, GateProtocol.MSG_PONG) { putString(GateProtocol.KEY_VERSION, BuildInfo.version(this@GateService)) }
                GateProtocol.MSG_CANCEL -> data.getString(GateProtocol.KEY_ITEM_ID)?.let { cancelFlags[it]?.set(true) }
                GateProtocol.MSG_DOWNLOAD -> startDownload(data, replyTo)
            }
        }
    }

    private fun startDownload(data: Bundle, replyTo: Messenger?) {
        val pfd = BundleCompat.getParcelable(data, GateProtocol.KEY_FD, ParcelFileDescriptor::class.java)
        val req = GateRequest(
            itemId = data.getString(GateProtocol.KEY_ITEM_ID).orEmpty(),
            url = data.getString(GateProtocol.KEY_URL).orEmpty(),
            sha256 = data.getString(GateProtocol.KEY_SHA256).orEmpty(),
            size = data.getLong(GateProtocol.KEY_SIZE),
            fileName = data.getString(GateProtocol.KEY_FILE_NAME).orEmpty(),
        )
        if (pfd == null) {
            reply(replyTo, GateProtocol.MSG_ERROR) { putString(GateProtocol.KEY_MESSAGE, "Hedef dosya verilmedi") }
            return
        }
        val flag = AtomicBoolean(false)
        cancelFlags[req.itemId] = flag
        enterForeground()
        executor.execute {
            val log = GateLog(this)
            try {
                val item = downloader.validate(req)
                val result = pfd.use { downloader.download(item, it.fileDescriptor, flag) { b, t -> reply(replyTo, GateProtocol.MSG_PROGRESS) { putLong(GateProtocol.KEY_BYTES, b); putLong(GateProtocol.KEY_TOTAL, t) } } }
                log.add("${item.fileName} · ${result.host} · tamam")
                reply(replyTo, GateProtocol.MSG_DONE) {
                    putLong(GateProtocol.KEY_BYTES, result.bytes)
                    putString(GateProtocol.KEY_HOST, result.host)
                    putString(GateProtocol.KEY_SHA256, result.sha256)
                }
            } catch (e: Exception) {
                runCatching { pfd.close() }
                val bytes = (e as? GateException)?.bytes ?: 0
                log.add("${req.fileName} · hata: ${e.message}")
                reply(replyTo, GateProtocol.MSG_ERROR) {
                    putString(GateProtocol.KEY_MESSAGE, e.message ?: e.javaClass.simpleName)
                    putLong(GateProtocol.KEY_BYTES, bytes)
                }
            } finally {
                cancelFlags.remove(req.itemId)
                mainHandler.post { leaveForeground() }
            }
        }
    }

    private fun reply(to: Messenger?, what: Int, fill: Bundle.() -> Unit) {
        to ?: return
        try {
            to.send(Message.obtain(null, what).apply { data = Bundle().apply(fill) })
        } catch (_: RemoteException) {
        }
    }
}

object BuildInfo {
    fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}

/** Last 50 gate events, shown on the status screen. Kale keeps the authoritative network log. */
class GateLog(context: Context) {
    private val prefs = context.getSharedPreferences("gate-log", Context.MODE_PRIVATE)
    fun entries(): List<String> = prefs.getString("log", "").orEmpty().lines().filter { it.isNotBlank() }
    fun add(line: String) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())
        val next = (entries() + "$stamp · $line").takeLast(50)
        prefs.edit().putString("log", next.joinToString("\n")).apply()
    }
}
