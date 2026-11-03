package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.knowledge.DocText
import io.github.ahmetdurmus1218.jarvis.ui.DocumentPick
import io.github.ahmetdurmus1218.jarvis.ui.MAX_DOCUMENT_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/** Picks a PDF / TXT / MD file with the system dialog and reads its text on this computer. */
object DesktopDocuments {
    suspend fun pick(): DocumentPick {
        val file = suspendCancellableCoroutine<File?> { cont ->
            SwingUtilities.invokeLater {
                val dialog = FileDialog(null as Frame?, "Belge seçin (PDF, TXT, MD)", FileDialog.LOAD)
                dialog.setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".pdf") || it.endsWith(".txt") || it.endsWith(".md") || it.endsWith(".markdown") } }
                dialog.isVisible = true
                cont.resume(dialog.files.firstOrNull())
            }
        } ?: return DocumentPick.Cancelled
        return read(file)
    }

    suspend fun read(file: File): DocumentPick = withContext(Dispatchers.IO) {
        try {
            require(file.length() <= MAX_DOCUMENT_BYTES) { "Belge çok büyük (en fazla ${MAX_DOCUMENT_BYTES / 1024 / 1024} MB)" }
            val text = if (file.name.lowercase().endsWith(".pdf")) {
                Loader.loadPDF(file).use { doc ->
                    require(!doc.isEncrypted) { "şifreli PDF'ler desteklenmiyor" }
                    PDFTextStripper().getText(doc)
                }
            } else {
                DocText.forFile(file.name, file.readBytes())
            }
            DocumentPick.Picked(file.nameWithoutExtension, text)
        } catch (e: Exception) {
            DocumentPick.Failed("Belge okunamadı: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
