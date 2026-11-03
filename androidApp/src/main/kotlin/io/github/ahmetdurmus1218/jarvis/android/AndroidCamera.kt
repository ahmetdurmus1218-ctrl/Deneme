package io.github.ahmetdurmus1218.jarvis.android

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.vision.FaceBox
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.core.vision.Point
import io.github.ahmetdurmus1218.jarvis.ui.VisionCamera
import io.github.ahmetdurmus1218.jarvis.ui.VisionFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import androidx.camera.core.Preview as CameraPreview

/**
 * The phone camera as Jarvis's eyes: CameraX preview + per-frame analysis, hands and faces tracked
 * on the device with MediaPipe (models from the lock file, hash-verified, loaded from the app's own
 * storage). Frames are never stored or sent anywhere; only the latest one is kept for "bu ne?".
 */
class AndroidCamera(
    private val app: Context,
    private val storage: AndroidModelStorage,
    private val registry: ModelRegistry,
    private val ensurePermission: suspend () -> Boolean,
    private val lifecycleOwner: () -> LifecycleOwner?,
) : VisionCamera {
    override val trackingGroup = GROUP

    @Volatile private var lastFrame: Bitmap? = null

    private fun modelPath(id: String): String? =
        registry.lock.items.firstOrNull { it.id == id }?.takeIf { storage.isInstalled(it) }?.let(storage::pathOf)

    override fun trackingReady(): Boolean = modelPath(HAND_MODEL) != null && modelPath(FACE_MODEL) != null

    @Composable
    override fun Preview(modifier: Modifier, frontCamera: Boolean, onFrame: (VisionFrame) -> Unit) {
        val context = LocalContext.current
        var granted by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(Unit) { granted = ensurePermission() }
        if (granted != true) {
            Box(modifier, contentAlignment = Alignment.Center) {
                if (granted == false) Text("Kamera izni gerekli. Telefon Ayarları → Uygulamalar → Jarvis → İzinler → Kamera.")
            }
            return
        }
        val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
        val ready = trackingReady()
        val trackers = remember(ready) { if (ready) Trackers.create(app, modelPath(HAND_MODEL)!!, modelPath(FACE_MODEL)!!) else null }
        var cameraError by remember { mutableStateOf<String?>(null) }
        DisposableEffect(trackers) { onDispose { trackers?.close() } }
        DisposableEffect(frontCamera, trackers) {
            val owner = lifecycleOwner()
            val executor = Executors.newSingleThreadExecutor()
            var provider: ProcessCameraProvider? = null
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                cameraError = NO_CAMERA
            } else {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    if (owner == null) return@addListener
                    // A missing or busy camera must never take the app down: say so on the screen instead.
                    try {
                        val p = future.get()
                        provider = p
                        val preview = CameraPreview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()
                        val wanted = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                        val other = if (frontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                        val selector = when {
                            p.hasCamera(wanted) -> wanted
                            p.hasCamera(other) -> other
                            else -> null
                        }
                        if (selector == null) {
                            cameraError = NO_CAMERA
                        } else {
                            val mirror = selector == CameraSelector.DEFAULT_FRONT_CAMERA
                            analysis.setAnalyzer(executor) { proxy -> analyze(proxy, mirror, trackers, onFrame) }
                            p.unbindAll()
                            p.bindToLifecycle(owner, selector, preview, analysis)
                            cameraError = null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "camera unavailable", e)
                        cameraError = "Kamera açılamadı. Başka bir uygulama kullanıyor olabilir; kapatıp tekrar deneyin."
                    }
                }, ContextCompat.getMainExecutor(context))
            }
            onDispose {
                runCatching { provider?.unbindAll() }
                executor.shutdown()
            }
        }
        Box(modifier) {
            AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize())
            cameraError?.let { msg ->
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { Text(msg) }
            }
        }
    }

    private var lastTs = 0L

    private fun analyze(proxy: ImageProxy, front: Boolean, trackers: Trackers?, onFrame: (VisionFrame) -> Unit) {
        try {
            val raw = proxy.toBitmap()
            // Upright and, for the selfie camera, mirrored like the preview, so overlay and image match.
            val m = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                if (front) postScale(-1f, 1f)
            }
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            lastFrame = bmp
            // MediaPipe's video mode needs strictly increasing timestamps.
            val ts = maxOf(SystemClock.uptimeMillis(), lastTs + 1).also { lastTs = it }
            val hands = trackers?.hands(bmp, ts).orEmpty()
            val faces = trackers?.faces(bmp, ts).orEmpty()
            onFrame(VisionFrame(hands, faces, bmp.width, bmp.height))
        } catch (e: Exception) {
            Log.w(TAG, "frame analysis failed", e)
        } finally {
            proxy.close()
        }
    }

    override suspend fun snapshotJpeg(): ByteArray? = withContext(Dispatchers.Default) {
        val bmp = lastFrame ?: return@withContext null
        val scale = minOf(1f, 1024f / maxOf(bmp.width, bmp.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    /** MediaPipe hand landmarker + face detector in video mode, models loaded from verified files. */
    internal class Trackers(private val hand: HandLandmarker, private val face: FaceDetector) : AutoCloseable {
        fun hands(bmp: Bitmap, ts: Long): List<Hand> =
            hand.detectForVideo(BitmapImageBuilder(bmp).build(), ts).landmarks().map { lm ->
                Hand(lm.map { Point(it.x(), it.y(), it.z()) })
            }

        fun faces(bmp: Bitmap, ts: Long): List<FaceBox> {
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            return face.detectForVideo(BitmapImageBuilder(bmp).build(), ts).detections().map { d ->
                val b = d.boundingBox()
                FaceBox(b.left / w, b.top / h, b.right / w, b.bottom / h, d.categories().firstOrNull()?.score() ?: 0f)
            }
        }

        override fun close() {
            runCatching { hand.close() }
            runCatching { face.close() }
        }

        companion object {
            private fun buffer(path: String): ByteBuffer =
                FileInputStream(path).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }

            fun create(context: Context, handPath: String, facePath: String): Trackers? = try {
                val hand = HandLandmarker.createFromOptions(
                    context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer(handPath)).build())
                        .setNumHands(2)
                        .setRunningMode(RunningMode.VIDEO)
                        .build(),
                )
                val face = FaceDetector.createFromOptions(
                    context,
                    FaceDetector.FaceDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer(facePath)).build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setMinDetectionConfidence(0.5f)
                        .build(),
                )
                Trackers(hand, face)
            } catch (e: Exception) {
                Log.w(TAG, "tracking models failed to load", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "JarvisCamera"
        const val GROUP = "vision-tracking"
        const val HAND_MODEL = "vision-hand-landmarker"
        const val FACE_MODEL = "vision-face-detector"
        const val NO_CAMERA = "Bu cihazda kamera bulunamadı."
    }
}
