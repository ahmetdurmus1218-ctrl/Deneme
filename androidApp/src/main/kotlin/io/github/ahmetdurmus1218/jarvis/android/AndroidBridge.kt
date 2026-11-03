package io.github.ahmetdurmus1218.jarvis.android

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.ui.AppFiles
import io.github.ahmetdurmus1218.jarvis.ui.ApprovalMethod
import io.github.ahmetdurmus1218.jarvis.ui.ApprovalResult
import io.github.ahmetdurmus1218.jarvis.ui.Approver
import io.github.ahmetdurmus1218.jarvis.ui.DeviceInfo
import io.github.ahmetdurmus1218.jarvis.ui.PlatformBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class AndroidFiles(context: Context) : AppFiles {
    private val dir = File(context.filesDir, "app").apply { mkdirs() }
    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()
    override fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }
    override fun append(name: String, line: String) = File(dir, name).appendText(line + "\n")
}

/** Host-side services for the shared UI. The current activity is attached/detached by MainActivity. */
class AndroidBridge(private val app: Context) : PlatformBridge {
    @Volatile var activity: MainActivity? = null

    override val platform = Platform.ANDROID
    override val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
    override val files: AppFiles = AndroidFiles(app)
    override val models = AndroidModelStorage(app) { activity?.pickDocument() }
    override val downloader = GateClient(app, models)
    override suspend fun pickDocument(): io.github.ahmetdurmus1218.jarvis.ui.DocumentPick {
        val act = activity ?: return io.github.ahmetdurmus1218.jarvis.ui.DocumentPick.Failed("Uygulama ön planda değil")
        val uri = act.pickDocument() ?: return io.github.ahmetdurmus1218.jarvis.ui.DocumentPick.Cancelled
        return AndroidDocuments.read(app, uri)
    }
    override val camera = AndroidCamera(
        app, models, io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry.bundled,
        ensurePermission = { activity?.ensurePermission(android.Manifest.permission.CAMERA) ?: false },
        lifecycleOwner = { activity },
    )
    override val zim = AndroidZim { AndroidZim.installedZim(models, io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry.bundled) }
    private val micPermission: suspend () -> Boolean = { activity?.ensurePermission(android.Manifest.permission.RECORD_AUDIO) ?: false }
    /** Whisper (Jarvis's own, hash-verified model) when installed, else the phone's on-device recognizer. */
    override val speechInput = PreferWhisperSpeechInput(
        WhisperSpeechInput(models, io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry.bundled, micPermission),
        AndroidSpeechInput(app, ensureMicPermission = micPermission, launch = { block -> activity?.launchOnUi(block) }),
    )
    override val micStream = AndroidMicStream(micPermission)

    /** A photo from the phone's gallery (coffee cup, bookshelf), scaled down and re-encoded as JPEG. */
    override suspend fun pickImageJpeg(): ByteArray? {
        val uri = activity?.pickImage() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val src = android.graphics.ImageDecoder.createSource(app.contentResolver, uri)
                val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
                    val max = maxOf(info.size.width, info.size.height)
                    if (max > 1600) d.setTargetSampleSize((max + 1599) / 1600)
                    d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                java.io.ByteArrayOutputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out); out.toByteArray() }
            }.getOrNull()
        }
    }
    override val cipher = KeystoreCipher()
    override fun keepScreenOn(on: Boolean) {
        val act = activity ?: return
        act.runOnUiThread {
            if (on) act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    override val speechOutput = PiperSpeechOutput(models, io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry.bundled, AndroidSpeechOutput(app)).also { it.warmUp() }

    override fun deviceInfo(): DeviceInfo {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val free = StatFs(app.filesDir.absolutePath).availableBytes
        val notes = buildList {
            if (!SpeechInputCheck.onDevice(app)) add("Cihaz içi ses tanıma bulunamadı; yazarak kullanabilirsiniz.")
            val ramGb = mi.totalMem / 1_073_741_824.0
            add(if (ramGb >= 11.5) "Önerilen model: Gemma 4 E4B" else "RAM 12 GB'tan az: Gemma 4 E2B önerilir")
        }
        return DeviceInfo("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", mi.totalMem / 1_073_741_824.0, free, notes)
    }

    override val approver = object : Approver {
        // BiometricPrompt must be created and shown on the main thread; downloads call this from a
        // background scope (they outlive the screen that started them).
        override suspend fun confirm(title: String, subtitle: String): ApprovalResult = withContext(Dispatchers.Main) {
            confirmOnMain(title, subtitle)
        }

        private suspend fun confirmOnMain(title: String, subtitle: String): ApprovalResult {
            val act = activity ?: return ApprovalResult.Rejected("Uygulama ön planda değil")
            val allowed = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            if (BiometricManager.from(act).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
                return ApprovalResult.Rejected("Ekran kilidi (PIN/desen/parmak izi) ayarlı değil. Güvenlik için onay verilemiyor.")
            }
            return suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(ApprovalResult.Approved(ApprovalMethod.BIOMETRIC))
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(ApprovalResult.Rejected(errString.toString()))
                    }
                })
                prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setAllowedAuthenticators(allowed)
                        .setConfirmationRequired(true)
                        .build(),
                )
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        }
    }

    override suspend fun execute(command: QuickCommand): String? = when (command) {
        is QuickCommand.SetAlarm -> start(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Saat uygulaması bulunamadı",
        )
        is QuickCommand.StartTimer -> start(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Zamanlayıcı uygulaması bulunamadı",
        )
        is QuickCommand.OpenApp -> openApp(command.appName)
        else -> null
    }

    private fun start(intent: Intent, notFound: String): String? {
        val act = activity ?: return "Uygulama ön planda değil"
        return try {
            act.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            notFound
        } catch (e: SecurityException) {
            e.message ?: notFound
        }
    }

    private fun categoryIntent(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)

    /** Opens apps with plain Intents only. Phone/SMS/contacts need an extra confirmation. */
    private suspend fun openApp(name: String): String? {
        val n = Turkish.normalize(name)
        val risky = setOf("telefon", "arama", "rehber", "kisiler", "mesajlar", "mesaj", "sms")
        if (n in risky) {
            val ok = approver.confirm("$name açılsın mı?", "Arama/mesaj uygulamaları için onay gerekir")
            if (ok !is ApprovalResult.Approved) return "Onay verilmedi"
        }
        val intent = when (n) {
            "kamera", "fotograf makinesi" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "ayarlar" -> Intent(Settings.ACTION_SETTINGS)
            "takvim" -> categoryIntent(Intent.CATEGORY_APP_CALENDAR)
            "galeri", "fotograflar", "resimler" -> categoryIntent(Intent.CATEGORY_APP_GALLERY)
            "harita", "haritalar" -> categoryIntent(Intent.CATEGORY_APP_MAPS)
            "hesap makinesi" -> categoryIntent(Intent.CATEGORY_APP_CALCULATOR)
            "muzik", "muzig", "muzikler" -> categoryIntent(Intent.CATEGORY_APP_MUSIC)
            "tarayici" -> categoryIntent(Intent.CATEGORY_APP_BROWSER)
            "e posta", "eposta", "posta" -> categoryIntent(Intent.CATEGORY_APP_EMAIL)
            "saat", "alarm", "alarmlar" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "telefon", "arama" -> Intent(Intent.ACTION_DIAL)
            "rehber", "kisiler" -> categoryIntent(Intent.CATEGORY_APP_CONTACTS)
            "mesajlar", "mesaj", "sms" -> categoryIntent(Intent.CATEGORY_APP_MESSAGING)
            else -> findLauncherApp(n)
        } ?: return "\"$name\" adında bir uygulama bulamadım"
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "\"$name\" açılamadı")
    }

    private fun findLauncherApp(normalizedName: String): Intent? {
        val pm = app.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(main, 0)
        }
        val match = apps.firstOrNull { Turkish.normalize(it.loadLabel(pm).toString()) == normalizedName }
            ?: apps.firstOrNull { Turkish.normalize(it.loadLabel(pm).toString()).contains(normalizedName) }
            ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
    }
}

object SpeechInputCheck {
    fun onDevice(context: Context) = android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
}

/** Lets suspend code wait for an Activity result. */
class PendingResult<T> {
    private var deferred: CompletableDeferred<T>? = null
    suspend fun await(start: () -> Unit): T {
        val d = CompletableDeferred<T>()
        deferred = d
        start()
        return d.await()
    }
    fun complete(value: T) {
        deferred?.complete(value)
        deferred = null
    }
}
