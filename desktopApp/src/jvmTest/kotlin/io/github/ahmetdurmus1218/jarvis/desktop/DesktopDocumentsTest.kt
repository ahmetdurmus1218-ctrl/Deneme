package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.ui.DocumentPick
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopDocumentsTest {
    private val dir = Files.createTempDirectory("docs").toFile()

    @Test
    fun pdfTextIsReadOnThisComputer() = runBlocking {
        val pdf = File(dir, "kira sozlesmesi.pdf")
        PDDocument().use { doc ->
            for (line in listOf("Aylik kira bedeli 18.500 TL.", "Depozito iki aylik kira tutarindadir.")) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText(line)
                    cs.endText()
                }
            }
            doc.save(pdf)
        }
        val r = DesktopDocuments.read(pdf)
        assertIs<DocumentPick.Picked>(r)
        assertEquals("kira sozlesmesi", r.name)
        assertTrue("18.500 TL" in r.text && "Depozito" in r.text, r.text)
    }

    @Test
    fun textFilesKeepTheirTurkishLetters() = runBlocking {
        val txt = File(dir, "not.txt")
        txt.writeBytes("Iğdır'da şöyle".toByteArray(charset("windows-1254")))
        val r = DesktopDocuments.read(txt)
        assertIs<DocumentPick.Picked>(r)
        assertEquals("Iğdır'da şöyle", r.text)
        val broken = File(dir, "bozuk.pdf").apply { writeText("PDF değil") }
        val f = DesktopDocuments.read(broken)
        assertIs<DocumentPick.Failed>(f)
        assertTrue("okunamadı" in f.message)
    }
}

class DesktopVisionTest {
    @kotlin.test.Test
    fun largePicturesAreShrunkToJpeg() {
        val img = java.awt.image.BufferedImage(4000, 2000, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val bytes = DesktopVision.jpeg(img)
        kotlin.test.assertEquals(0xFF.toByte(), bytes[0])
        kotlin.test.assertEquals(0xD8.toByte(), bytes[1], "JPEG")
        val back = javax.imageio.ImageIO.read(bytes.inputStream())
        kotlin.test.assertEquals(1600, back.width)
        kotlin.test.assertEquals(800, back.height)
    }
}
