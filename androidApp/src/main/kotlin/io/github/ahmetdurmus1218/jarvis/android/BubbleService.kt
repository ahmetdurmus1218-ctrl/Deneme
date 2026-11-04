package io.github.ahmetdurmus1218.jarvis.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.ahmetdurmus1218.jarvis.core.stt.ClapDetector
import io.github.ahmetdurmus1218.jarvis.core.stt.WakePhrase
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.FloatingRick
import io.github.ahmetdurmus1218.jarvis.ui.components.RickFace
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Rick floating over other apps: the living face in a small window of its own. Tap: Rick listens
 * (tap again to stop). Hold: open Rick. Drag: move it. After a few quiet minutes it falls asleep;
 * two claps (if allowed) or a tap wake it, it greets and listens. The microphone is only used on
 * the device, for claps while asleep and for speech after a tap; nothing is recorded or kept.
 *
 * It does not reach into other apps: no messages, no payments, no banking. Its commands are the
 * same as in Rick's own window.
 */
class BubbleService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private val sleeping = MutableStateFlow(false)
    private var clapJob: Job? = null

    private val app get() = application as JarvisApplication
    private val wm get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.graph.settings.update { it.copy(bubble = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (view == null) {
            if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
            show()
            watchIdle()
        }
        running.value = true
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ekrandaki Rick", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Rick ekranda")
            .setContentText("Dokunun: konuşun · basılı tutun: Rick'i açın · uyurken iki alkış")
            .setContentIntent(open)
            .addAction(0, "Kapat", stop)
            .setOngoing(true)
            .build()
        val mic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val types = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, types)
    }

    private fun show() {
        val controller = app.graph.sharedController ?: return
        val density = resources.displayMetrics.density
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - 120 * density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
            title = WINDOW_TITLE
        }
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BubbleService)
            setViewTreeSavedStateRegistryOwner(this@BubbleService)
            setViewTreeViewModelStoreOwner(this@BubbleService)
            setContent {
                val mode by controller.mode.collectAsState()
                val level by controller.level.collectAsState()
                val subtitle by controller.subtitle.collectAsState()
                val asleep by sleeping.collectAsState()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RickFace(
                        mode, level,
                        Modifier
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { tapped() },
                                    onLongPress = { openRick() },
                                )
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    params?.let { lp ->
                                        lp.x += drag.x.toInt()
                                        lp.y += drag.y.toInt()
                                        runCatching { wm.updateViewLayout(view, lp) }
                                    }
                                }
                            },
                        size = 96.dp,
                        sleeping = asleep,
                    )
                    // What Rick is saying, under the face (only while speaking).
                    if (mode == CoreMode.SPEAKING && subtitle.isNotBlank()) {
                        Text(
                            subtitle, color = JarvisColors.Text, fontSize = 12.sp, maxLines = 3,
                            modifier = Modifier.widthIn(max = 200.dp)
                                .background(JarvisColors.Surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        wm.addView(v, p)
        view = v
        params = p
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun tapped() {
        val controller = app.graph.sharedController ?: return
        if (sleeping.value) { wake(byClaps = false); return }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openRick()
            return
        }
        stopClaps()
        controller.toggleListening()
    }

    private fun openRick() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Falls asleep after the quiet time in Settings (at least two minutes) without talking. */
    private fun watchIdle() {
        val controller = app.graph.sharedController ?: return
        scope.launch {
            var quietSince = System.currentTimeMillis()
            while (true) {
                delay(5_000)
                val s = app.graph.settings.state.value
                if (controller.mode.value != CoreMode.IDLE) quietSince = System.currentTimeMillis()
                val limit = maxOf(2, s.sleepAfterMin.takeIf { it > 0 } ?: 5) * 60_000L
                if (!sleeping.value && System.currentTimeMillis() - quietSince > limit) {
                    sleeping.value = true
                    listenForClaps()
                }
                if (sleeping.value && controller.mode.value != CoreMode.IDLE) { sleeping.value = false; stopClaps(); quietSince = System.currentTimeMillis() }
            }
        }
    }

    /** Asleep: two claps (or the user's rhythm) wake Rick. Only loudness jumps are looked at. */
    private fun listenForClaps() {
        val s = app.graph.settings.state.value
        if (!s.bubbleClapWake) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val mic = app.bridge.micStream ?: return
        stopClaps()
        clapJob = scope.launch {
            val detector = ClapDetector(threshold = s.clapThreshold).apply { rhythm = s.clapRhythm }
            val ok = mic.start { samples, n -> if (detector.feed(samples, n)) scope.launch { wake(byClaps = true) } }
            if (!ok) Log.w(TAG, "microphone not available for claps")
        }
    }

    private fun stopClaps() {
        clapJob?.cancel()
        clapJob = null
        runCatching { app.bridge.micStream?.stop() }
    }

    private fun wake(byClaps: Boolean) {
        if (!sleeping.value) return
        sleeping.value = false
        stopClaps()
        val controller = app.graph.sharedController ?: return
        val greeting = WakePhrase.greeting(NowProvider.System.now().hour)
        if (app.graph.webReady()) controller.offerNews(greeting, listen = true) else controller.greet(greeting + " Dinliyorum.", listen = true)
        Log.i(TAG, "woke up (claps=$byClaps)")
    }

    override fun onDestroy() {
        running.value = false
        stopClaps()
        scope.cancel()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RickBubble"
        const val WINDOW_TITLE = "RickBubble"
        private const val CHANNEL = "rick-bubble"
        private const val NOTIFICATION_ID = 41
        const val ACTION_STOP = "io.github.ahmetdurmus1218.jarvis.BUBBLE_STOP"
        val running = MutableStateFlow(false)
    }
}

/** [FloatingRick] for the phone: the overlay permission and the [BubbleService]. */
class AndroidFloatingRick(private val context: Context, private val activity: () -> MainActivity?) : FloatingRick {
    override fun allowed(): Boolean = Settings.canDrawOverlays(context)

    override fun askPermission() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { (activity() ?: context).startActivity(i) }
    }

    override suspend fun start(): String? {
        if (!allowed()) return "Önce \"Diğer uygulamaların üzerinde göster\" iznini verin."
        // Asked while Rick is on screen, so the service may keep the microphone in the background.
        activity()?.ensurePermission(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) activity()?.ensurePermission(android.Manifest.permission.POST_NOTIFICATIONS)
        return try {
            ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
            null
        } catch (e: Exception) {
            "Başlatılamadı: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun stop() {
        context.stopService(Intent(context, BubbleService::class.java))
    }

    override val running: StateFlow<Boolean> = BubbleService.running.asStateFlow()
}
