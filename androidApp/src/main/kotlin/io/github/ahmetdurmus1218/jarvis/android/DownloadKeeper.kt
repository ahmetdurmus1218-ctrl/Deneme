package io.github.ahmetdurmus1218.jarvis.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps Rick alive while an approved download runs (Wikipedia is 3.7 GB and takes a long time): a
 * foreground service with a notification, so Android neither kills nor freezes Rick when the
 * screen goes off or the user switches apps, and the gate's progress and "done" messages arrive.
 * It does nothing else; the download itself runs in Rick Kapı. Rick still has no INTERNET permission.
 */
class DownloadKeeper : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Rick dosya indiriyor")
            .setContentText("Ekranı kapatabilir, başka uygulamaya geçebilirsiniz; kopan indirme kaldığı yerden sürer.")
            .setOngoing(true)
            .build()
        runCatching { ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            .onFailure { Log.w(TAG, "foreground refused: ${it.message}"); stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "RickDownload"
        private const val CHANNEL = "rick-downloads"
        private const val ID = 43

        fun keep(context: Context, on: Boolean) {
            val i = Intent(context, DownloadKeeper::class.java)
            runCatching { if (on) ContextCompat.startForegroundService(context, i) else context.stopService(i) }
                .onFailure { Log.w(TAG, "keeper: ${it.message}") }
        }
    }
}
