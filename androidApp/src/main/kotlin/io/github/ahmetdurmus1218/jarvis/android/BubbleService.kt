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
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.ahmetdurmus1218.jarvis.core.stt.WakePhrase
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.FloatingRick
import io.github.ahmetdurmus1218.jarvis.ui.components.RickFace
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var handsWanted = false
    private var handsAllowed = false
    private var stopCamera: (() -> Unit)? = null
    private val nav = io.github.ahmetdurmus1218.jarvis.core.vision.HandNavigator()
    /** Where the hand is (-1…1 across the screen), so the face looks at it; and the last hand command. */
    private val handAt = MutableStateFlow<androidx.compose.ui.geometry.Offset?>(null)
    private val handNote = MutableStateFlow<String?>(null)
    /** Full-screen, untouchable layer that shows the hand's cursor over the app being driven. */
    private var cursorView: ComposeView? = null
    private val cursor = MutableStateFlow<androidx.compose.ui.geometry.Offset?>(null)
    /** The camera was switched off after [CAMERA_IDLE_MS] without a hand; a tap switches it on again. */
    private val cameraResting = MutableStateFlow(false)
    private var lastHandAt = 0L
    private var scrollAcc = 0f

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
        handsWanted = intent?.getBooleanExtra(EXTRA_HANDS, false) == true
        goForeground()
        if (view == null) {
            if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
            show()
            watchIdle()
            watchHands()
        }
        running.value = true
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ekrandaki Rick", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val camOn = handsWanted && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (camOn) "Rick ekranda · kamera açık (el hareketleri)" else "Rick ekranda")
            .setContentText(
                if (camOn) "Görüntü kaydedilmez, telefondan çıkmaz · 10 dk el görmezse kamera kapanır · dokunun: konuşun"
                else "Dokunun: konuşun · basılı tutun: Rick'i açın · uyurken iki alkış",
            )
            .setContentIntent(open)
            .addAction(0, "Kapat", stop)
            .setOngoing(true)
            .build()
        val mic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cam = handsWanted && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val extra = (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or (if (cam) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        val types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or extra else extra
        handsAllowed = cam
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
                val look by handAt.collectAsState()
                val note by handNote.collectAsState()
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
                        lookAt = look,
                    )
                    note?.let { n ->
                        Text(
                            n, color = JarvisColors.Background, fontSize = 12.sp,
                            modifier = Modifier.background(JarvisColors.Amber, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
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
        Log.i(TAG, "tap")
        if (cameraResting.value) { cameraResting.value = false; say("📷 Kamera yeniden açık") }
        val controller = app.graph.sharedController ?: return
        if (sleeping.value) { wake(byClaps = false); return }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openRick()
            return
        }
        controller.toggleListening()
    }

    private fun openRick() {
        Log.i(TAG, "open Rick")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /**
     * Hand gestures while another app is in front (the music app, say): swipe for the next / previous
     * song, ✋ hold to pause / play, 🤏 turn for the volume, 👍 hold to talk, ✊ hold to quiet Rick.
     * Paused while Rick's own window is in front (its "El ile yönet" has the camera then).
     */
    private fun watchHands() {
        if (!handsAllowed) return
        val camera = app.bridge.camera as? AndroidCamera ?: return
        val controller = app.graph.sharedController ?: return
        // The camera rests after a while without a hand (privacy and battery); a tap wakes it.
        scope.launch {
            while (true) {
                delay(30_000)
                if (stopCamera != null && !cameraResting.value && android.os.SystemClock.uptimeMillis() - lastHandAt > CAMERA_IDLE_MS) {
                    cameraResting.value = true
                    say("📷 Kamerayı kapattım (10 dk el yok). Dokununca açılır.", 6_000)
                    Log.i(TAG, "camera resting")
                }
            }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(appInFront, cameraResting) { front, resting -> front || resting }.collect { off ->
                stopCamera?.invoke()
                stopCamera = null
                nav.reset()
                handAt.value = null
                cursor.value = null
                if (off) return@collect
                lastHandAt = android.os.SystemClock.uptimeMillis()
                val size = screenSize()
                val w = size.first
                val h = size.second
                stopCamera = camera.startBackground(this@BubbleService) { f ->
                    // The inner part of the picture spans the screen: its edges are hard to reach.
                    val hand = f.hands.firstOrNull()?.let { hd ->
                        io.github.ahmetdurmus1218.jarvis.core.vision.Hand(
                            hd.points.map { p ->
                                io.github.ahmetdurmus1218.jarvis.core.vision.Point(((p.x - 0.12f) / 0.76f).coerceIn(0f, 1f) * w, ((p.y - 0.12f) / 0.76f).coerceIn(0f, 1f) * h, p.z)
                            },
                        )
                    }
                    scope.launch {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (hand != null) lastHandAt = now
                        val r = nav.update(now, hand, w, h)
                        handAt.value = f.hands.firstOrNull()?.points?.get(9)?.let { androidx.compose.ui.geometry.Offset(it.x * 2f - 1f, it.y * 2f - 1f) }
                        // The app in front is driven only if the user chose it, it is never a bank / messenger /
                        // settings app, the accessibility service is on, and the phone is unlocked.
                        val acc = RickAccessibility.instance
                        val drive = acc != null && !sleeping.value && !locked() &&
                            io.github.ahmetdurmus1218.jarvis.core.device.HandApps.allowed(RickAccessibility.front.value, app.graph.settings.state.value.handApps)
                        if (drive) ensureCursorWindow()
                        cursor.value = if (drive) nav.pointer.state?.let { androidx.compose.ui.geometry.Offset(it.x, it.y) } else null
                        if (drive && acc != null) for (e in r.pointerEvents) when (e) {
                            is io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Event.Click -> { acc.tap(e.x, e.y); say("👆 Dokundum") }
                            is io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Event.Scroll -> {
                                // Pinch-drag: the page follows the hand, one swipe per stretch of movement.
                                scrollAcc += e.dy
                                if (kotlin.math.abs(scrollAcc) > h * 0.12f) {
                                    val d = scrollAcc.coerceIn(-h * 0.4f, h * 0.4f)
                                    scrollAcc = 0f
                                    acc.swipe(w / 2f, h / 2f - d / 2f, w / 2f, h / 2f + d / 2f)
                                }
                            }
                            else -> {}
                        }
                        for (c in r.commands) {
                            if (sleeping.value) { wake(byClaps = false); continue }
                            val done = if (drive && acc != null) drive(acc, c, w, h) else null
                            (done ?: controller.onHand(c, null))?.let { say(it) }
                        }
                    }
                }
                if (stopCamera == null) Log.w(TAG, "hand gestures unavailable (tracking models or camera missing)")
            }
        }
    }

    /** A hand command carried out in the app in front; null when it is not about that app (music keys, Rick). */
    private fun drive(acc: RickAccessibility, c: io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand, w: Float, h: Float): String? {
        val music = app.bridge.mediaPlaying()
        return when (c) {
            io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.SCROLL_DOWN -> { acc.swipe(w / 2f, h * 0.72f, w / 2f, h * 0.28f); "⬇ Aşağı" }
            io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.SCROLL_UP -> { acc.swipe(w / 2f, h * 0.28f, w / 2f, h * 0.72f); "⬆ Yukarı" }
            io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.NEXT -> if (music) null else { acc.swipe(w * 0.8f, h / 2f, w * 0.2f, h / 2f); "⬅ Sonraki" }
            io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.PREVIOUS -> if (music) null else { acc.swipe(w * 0.2f, h / 2f, w * 0.8f, h / 2f); "➡ Önceki" }
            io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.BACK -> { acc.back(); "↩ Geri" }
            io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.HOME -> { acc.home(); "🏠 Ana ekran" }
            else -> null
        }
    }

    private fun say(note: String, ms: Long = 1_800) {
        handNote.value = note
        scope.launch { delay(ms); if (handNote.value == note) handNote.value = null }
    }

    private fun locked(): Boolean = (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked

    private fun screenSize(): Pair<Float, Float> {
        val b = wm.currentWindowMetrics.bounds
        return b.width().toFloat() to b.height().toFloat()
    }

    /** The cursor layer: covers the screen, takes no touches, and stays at 80 % opacity so Android lets touches through. */
    private fun ensureCursorWindow() {
        if (cursorView != null) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.8f
            title = "RickCursor"
        }
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BubbleService)
            setViewTreeSavedStateRegistryOwner(this@BubbleService)
            setViewTreeViewModelStoreOwner(this@BubbleService)
            setContent {
                val at by cursor.collectAsState()
                androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) {
                    val c = at ?: return@Canvas
                    val r = 18.dp.toPx()
                    drawCircle(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.3f), r * 1.15f, c)
                    drawCircle(androidx.compose.ui.graphics.Color(0xFFFFB020), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    drawCircle(androidx.compose.ui.graphics.Color(0xFFFFB020), if (nav.pointer.state?.pinching == true) r * 0.55f else r * 0.2f, c)
                }
            }
        }
        runCatching { wm.addView(v, p) }.onSuccess { cursorView = v }.onFailure { Log.w(TAG, "cursor layer: ${it.message}") }
    }

    /** Falls asleep after the quiet time in Settings (at least two minutes) without talking. */
    private fun watchIdle() {
        val controller = app.graph.sharedController ?: return
        // The shared conversation keeps the microphone while idle: Rick's name and two claps work over
        // other apps too. Asleep, the claps wake the floating Rick; awake, they open the microphone.
        controller.onClaps = {
            Log.i(TAG, "claps")
            if (sleeping.value) { wake(byClaps = true); true } else { say("👏 Dinliyorum"); false }
        }
        controller.setFloating(true)
        scope.launch {
            var quietSince = System.currentTimeMillis()
            while (true) {
                delay(5_000)
                val s = app.graph.settings.state.value
                if (controller.mode.value != CoreMode.IDLE) quietSince = System.currentTimeMillis()
                val limit = maxOf(2, s.sleepAfterMin.takeIf { it > 0 } ?: 5) * 60_000L
                if (!sleeping.value && System.currentTimeMillis() - quietSince > limit) sleeping.value = true
                if (sleeping.value && controller.mode.value != CoreMode.IDLE) { sleeping.value = false; quietSince = System.currentTimeMillis() }
            }
        }
    }

    private fun wake(byClaps: Boolean) {
        if (!sleeping.value) return
        sleeping.value = false
        cameraResting.value = false
        val controller = app.graph.sharedController ?: return
        val greeting = WakePhrase.greeting(NowProvider.System.now().hour)
        if (app.graph.webReady()) controller.offerNews(greeting, listen = true) else controller.greet(greeting + " Dinliyorum.", listen = true)
        Log.i(TAG, "woke up (claps=$byClaps)")
    }

    override fun onDestroy() {
        running.value = false
        stopCamera?.invoke()
        stopCamera = null
        app.graph.sharedController?.let { it.setFloating(false); it.onClaps = null }
        scope.cancel()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        cursorView?.let { runCatching { wm.removeView(it) } }
        cursorView = null
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
        const val EXTRA_HANDS = "hands"
        private const val CAMERA_IDLE_MS = 10 * 60_000L

        /** True while Rick's own window is in front (the window's hand control has the camera then). */
        val appInFront = MutableStateFlow(false)
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

    override suspend fun start(hands: Boolean): String? {
        if (!allowed()) return "Önce \"Diğer uygulamaların üzerinde göster\" iznini verin."
        // Asked while Rick is on screen, so the service may keep the microphone (and camera) in the background.
        activity()?.ensurePermission(android.Manifest.permission.RECORD_AUDIO)
        if (hands) activity()?.ensurePermission(android.Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) activity()?.ensurePermission(android.Manifest.permission.POST_NOTIFICATIONS)
        return try {
            // A running service is stopped first, so its foreground types (camera or not) are set anew.
            if (BubbleService.running.value) context.stopService(Intent(context, BubbleService::class.java))
            ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java).putExtra(BubbleService.EXTRA_HANDS, hands))
            null
        } catch (e: Exception) {
            "Başlatılamadı: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun stop() {
        context.stopService(Intent(context, BubbleService::class.java))
    }

    override val running: StateFlow<Boolean> = BubbleService.running.asStateFlow()

    override fun appControlOn(): Boolean {
        val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return on.split(':').any { it.startsWith(context.packageName + "/") && it.endsWith("RickAccessibility") }
    }

    override fun openAppControlSettings() {
        runCatching { (activity() ?: context).startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun appChoices(): List<Pair<String, String>> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { (pkg, label) -> pkg != context.packageName && !io.github.ahmetdurmus1218.jarvis.core.device.HandApps.neverAllowed(pkg, label) }
            .sortedBy { it.second.lowercase() }
    }
}
