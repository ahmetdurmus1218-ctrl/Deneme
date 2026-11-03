package io.github.ahmetdurmus1218.jarvis.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/** Pictures for "ekranda ne var / bu resimde ne var" on the PC; they stay on this computer. */
object DesktopVision {
    /** The whole desktop (all screens) as one JPEG, at most 1600 px wide. */
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            if (GraphicsEnvironment.isHeadless()) return@withContext null
            val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .map { it.defaultConfiguration.bounds }
                .fold(Rectangle()) { acc, r -> acc.union(r) }
            jpeg(Robot().createScreenCapture(bounds))
        }.getOrNull()
    }

    suspend fun pickImage(): ByteArray? {
        val file = suspendCancellableCoroutine<File?> { cont ->
            SwingUtilities.invokeLater {
                val dialog = FileDialog(null as Frame?, "Resim seçin", FileDialog.LOAD)
                dialog.setFilenameFilter { _, n -> n.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".bmp") || it.endsWith(".gif") } }
                dialog.isVisible = true
                cont.resume(dialog.files.firstOrNull())
            }
        } ?: return null
        return withContext(Dispatchers.IO) { runCatching { ImageIO.read(file)?.let(::jpeg) }.getOrNull() }
    }

    /** RGB, scaled so the longer side is at most [max] pixels, JPEG quality ~0.85 (ImageIO default). */
    fun jpeg(img: BufferedImage, max: Int = 1600): ByteArray {
        val scale = minOf(1.0, max.toDouble() / maxOf(img.width, img.height))
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        val rgb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null)
            dispose()
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(rgb, "jpg", out)
            out.toByteArray()
        }
    }
}
