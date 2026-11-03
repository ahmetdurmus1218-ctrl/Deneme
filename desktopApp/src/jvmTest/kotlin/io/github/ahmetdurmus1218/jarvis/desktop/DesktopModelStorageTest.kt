package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.desktop.kapi.DesktopKapi
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Bul": model files already on the PC are taken over after a hash check, never downloaded again. */
class DesktopModelStorageTest {
    private val tmp = Files.createTempDirectory("model-search").toFile()

    private fun item(name: String, body: ByteArray, id: String = name) = File(tmp, "probe-$id").let { f ->
        f.writeBytes(body)
        val (sha, size) = DesktopKapi.sha256(f)
        f.delete()
        LockedItem(id, "llm", listOf("desktop"), id, "g", "https://huggingface.co/x/$name", "huggingface.co", name, size, sha, "r", "o")
    }

    @Test
    fun findsLinksAndVerifiesAFileInDownloads() = runBlocking {
        val body = ByteArray(4096) { (it * 7).toByte() }
        val model = item("model.gguf", body)
        val downloads = File(tmp, "Downloads/sub").apply { mkdirs() }
        File(downloads, "Model.GGUF").writeBytes(body) // name case does not matter
        val storage = DesktopModelStorage(File(tmp, "data/models"), File(tmp, "data/partial")) { listOf(File(tmp, "Downloads")) }
        val seen = mutableListOf<String>()
        assertEquals(setOf(model.id), storage.adoptExisting(listOf(model)) { seen += it.id })
        assertEquals(listOf(model.id), seen)
        assertTrue(storage.isInstalled(model))
        assertTrue(File(downloads, "Model.GGUF").isFile, "the user's own copy is left where it was")
        assertEquals(body.toList(), File(storage.pathOf(model)).readBytes().toList())
        // Second run: nothing left to find.
        assertEquals(emptySet(), storage.adoptExisting(listOf(model)))
    }

    @Test
    fun aFileWithTheRightNameButOtherContentIsNotTakenOver() = runBlocking {
        val model = item("voice.onnx", ByteArray(100) { 1 })
        val dir = File(tmp, "Desktop").apply { mkdirs() }
        File(dir, "voice.onnx").writeBytes(ByteArray(100) { 2 }) // same size, different bytes
        val storage = DesktopModelStorage(File(tmp, "d/models"), File(tmp, "d/partial")) { listOf(dir) }
        var candidates = 0
        assertEquals(emptySet(), storage.adoptExisting(listOf(model)) { candidates++ })
        assertEquals(1, candidates, "it was looked at")
        assertFalse(storage.isInstalled(model))
        assertTrue(File(tmp, "d/partial").listFiles().orEmpty().isEmpty(), "no leftover temp file")
    }

    @Test
    fun ollamaBlobsAreFoundByTheirHashName() = runBlocking {
        val body = ByteArray(2048) { (it % 13).toByte() }
        val model = item("qwen.gguf", body)
        val blobs = File(tmp, "ollama/blobs").apply { mkdirs() }
        File(blobs, "sha256-${model.sha256}").writeBytes(body)
        val storage = DesktopModelStorage(File(tmp, "o/models"), File(tmp, "o/partial")) { listOf(blobs) }
        assertEquals(setOf(model.id), storage.adoptExisting(listOf(model)))
    }

    @Test
    fun aFolderTheUserAddedIsSearchedToo() = runBlocking {
        val body = ByteArray(512) { 9 }
        val model = item("wiki.zim", body)
        val usb = File(tmp, "E-drive/Modeller").apply { mkdirs() }
        File(usb, "wiki.zim").writeBytes(body)
        val storage = DesktopModelStorage(File(tmp, "u/models"), File(tmp, "u/partial")) { emptyList() }
        assertEquals(emptySet(), storage.adoptExisting(listOf(model)))
        storage.addSearchFolder(usb)
        assertEquals(setOf(model.id), storage.adoptExisting(listOf(model)))
    }

    @Test
    fun theOldJarvisFolderIsMovedOnceSoNothingIsDownloadedAgain() {
        val base = File(tmp, "LocalAppData")
        val legacy = File(base, "Jarvis/models").apply { mkdirs() }
        File(legacy, "m.gguf").writeText("x")
        val dir = dataDirIn(base)
        assertEquals("Rick", dir.name)
        assertTrue(File(dir, "models/m.gguf").isFile)
        assertFalse(File(base, "Jarvis").exists())
        // Later starts leave an existing Rick folder alone.
        File(base, "Jarvis").mkdirs()
        assertEquals(dir, dataDirIn(base))
        assertTrue(File(base, "Jarvis").exists())
    }
}
