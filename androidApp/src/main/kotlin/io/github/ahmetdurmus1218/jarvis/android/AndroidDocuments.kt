package io.github.ahmetdurmus1218.jarvis.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.ahmetdurmus1218.jarvis.core.knowledge.DocText
import io.github.ahmetdurmus1218.jarvis.ui.DocumentPick
import io.github.ahmetdurmus1218.jarvis.ui.MAX_DOCUMENT_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads a picked PDF / TXT / MD file on the device (PDF text with PdfBox-Android). */
object AndroidDocuments {
    suspend fun read(context: Context, uri: Uri): DocumentPick = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "doc-" + System.nanoTime())
        try {
            val name = displayName(context, uri) ?: "Belge"
            var total = 0L
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Dosya açılamadı" }
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_DOCUMENT_BYTES) { "Belge çok büyük (en fazla ${MAX_DOCUMENT_BYTES / 1024 / 1024} MB)" }
                        out.write(buf, 0, n)
                    }
                }
            }
            val mime = context.contentResolver.getType(uri).orEmpty()
            val isPdf = name.lowercase().endsWith(".pdf") || mime == "application/pdf"
            val text = if (isPdf) pdfText(context, tmp) else DocText.forFile(name, tmp.readBytes())
            DocumentPick.Picked(name.substringBeforeLast('.', name), text)
        } catch (e: OutOfMemoryError) {
            DocumentPick.Failed("Belge bu telefon için çok büyük")
        } catch (e: Exception) {
            DocumentPick.Failed("Belge okunamadı: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            tmp.delete()
        }
    }

    private fun pdfText(context: Context, file: File): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return try {
            PDDocument.load(file).use { doc ->
                require(!doc.isEncrypted) { "şifreli PDF'ler desteklenmiyor" }
                PDFTextStripper().getText(doc)
            }
        } catch (e: NoClassDefFoundError) {
            // Encryption support (BouncyCastle) is not packaged to keep the app small.
            throw IllegalArgumentException("şifreli PDF'ler desteklenmiyor")
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
