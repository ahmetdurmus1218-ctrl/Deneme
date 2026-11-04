package io.github.ahmetdurmus1218.jarvis.android

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.ahmetdurmus1218.jarvis.ui.JarvisApp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private val permissionResult = PendingResult<Boolean>()
    private val documentResult = PendingResult<Uri?>()
    private val folderResult = PendingResult<Uri?>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionResult.complete(it) }
    private val documentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { documentResult.complete(it) }
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderResult.complete(it) }

    private val bridge get() = (application as JarvisApplication).bridge

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        bridge.activity = this
        val graph = (application as JarvisApplication).graph
        val fmt = SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.forLanguageTag("tr-TR"))
        setContent { JarvisApp(graph) { fmt.format(Date(it)) } }
        // Tapjacking guard: a touch that passes through another app's window drawn over Rick (an
        // invisible overlay tricking the user into "Onayla") is ignored. Rick's own floating face is
        // the same app and does not count.
        window.decorView.filterTouchesWhenObscured = true
        // The back button closes what is open in Rick first (camera, panels, the approval); only then leaves.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi.backHandler?.invoke() == true) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        bridge.activity = this
        BubbleService.appInFront.value = true
        bridge.downloader.refresh()
        // Large model downloads can take a while; keep the screen on while Rick is in front.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        BubbleService.appInFront.value = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        if (bridge.activity === this) bridge.activity = null
        super.onDestroy()
    }

    suspend fun ensurePermission(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return true
        return permissionResult.await { permissionLauncher.launch(permission) }
    }

    suspend fun pickDocument(): Uri? = documentResult.await { documentLauncher.launch(arrayOf("*/*")) }

    suspend fun pickImage(): Uri? = documentResult.await { documentLauncher.launch(arrayOf("image/*")) }

    /** A folder the user grants read access to (for example Download, where model files were copied). */
    suspend fun pickFolder(): Uri? = folderResult.await { folderLauncher.launch(null) }

    fun launchOnUi(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }
}
