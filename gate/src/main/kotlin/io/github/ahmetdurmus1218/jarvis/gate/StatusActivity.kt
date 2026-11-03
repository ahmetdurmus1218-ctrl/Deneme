package io.github.ahmetdurmus1218.jarvis.gate

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.net.DownloadPolicy

/** The gate has no real UI: this screen only explains what it is and shows its recent activity. */
class StatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(s: String, size: Float = 15f, color: Int = Color.rgb(230, 241, 250)) = TextView(this).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setPadding(0, pad / 3, 0, pad / 3)
        }
        root.addView(text("Rick Kapı ${BuildInfo.version(this)}", 24f, Color.rgb(255, 181, 71)))
        root.addView(
            text(
                "Bu uygulama Rick'in internete çıkabilen TEK parçasıdır. Kendi başına hiçbir istek yapmaz; " +
                    "yalnızca Rick'te sizin parmak izi/PIN onayınızdan sonra, kilit dosyasındaki dosyaları indirir.",
            ),
        )
        root.addView(text("İzinli alan adları", 17f))
        root.addView(text(DownloadPolicy.ALLOWED_HOSTS.joinToString("\n"), 13f))
        root.addView(text("İzinli dosya türleri", 17f))
        root.addView(text(DownloadPolicy.ALLOWED_EXTENSIONS.joinToString("  "), 13f))
        root.addView(text("Kilitli dosya sayısı: ${ModelRegistry.bundled.lock.items.size}", 13f))
        root.addView(text("Son işlemler", 17f))
        val log = GateLog(this).entries()
        root.addView(text(if (log.isEmpty()) "Henüz işlem yok." else log.asReversed().joinToString("\n"), 13f))
        root.addView(text("Kapı'yı kaldırırsanız Rick tamamen çevrimdışı çalışmaya devam eder.", 13f, Color.rgb(138, 160, 180)))
        setContentView(ScrollView(this).apply { addView(root) })
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Only so the download notification is visible; downloads work without it.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
