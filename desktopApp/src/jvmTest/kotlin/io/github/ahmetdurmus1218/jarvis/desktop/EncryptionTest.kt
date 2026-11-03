package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import io.github.ahmetdurmus1218.jarvis.core.memory.PersistentMemoryStore
import io.github.ahmetdurmus1218.jarvis.ui.EncryptedFiles
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Data at rest on the PC: sealed with AES-GCM; on Windows the key itself is sealed with DPAPI. */
class EncryptionTest {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Test
    fun memoryAndLogsAreUnreadableOnDisk() = runBlocking {
        val dir = Files.createTempDirectory("jarvis-enc").toFile()
        val keyFile = File(dir, "key.dpapi")
        val plainFiles = DesktopFiles(File(dir, "app"))
        val files = EncryptedFiles(plainFiles, DesktopCipher(keyFile))
        val store = object : TextStore {
            override fun read(name: String) = files.read(name)
            override fun write(name: String, text: String) = files.write(name, text)
        }
        PersistentMemoryStore(store).add("Kullanıcının adı Ahmet, Manisa'da yaşıyor.")
        files.append("network-log.jsonl", "{\"host\":\"huggingface.co\"}")
        files.append("network-log.jsonl", "{\"host\":\"github.com\"}")

        val onDisk = File(dir, "app").listFiles()!!.joinToString("\n") { it.readText() }
        assertFalse("Ahmet" in onDisk || "Manisa" in onDisk || "huggingface" in onDisk, onDisk.take(300))
        assertTrue(onDisk.contains(EncryptedFiles.PREFIX))

        // A new process with the same key reads everything back.
        val again = EncryptedFiles(DesktopFiles(File(dir, "app")), DesktopCipher(keyFile))
        val mem = PersistentMemoryStore(object : TextStore {
            override fun read(name: String) = again.read(name)
            override fun write(name: String, text: String) = again.write(name, text)
        })
        assertEquals(listOf("Kullanıcının adı Ahmet, Manisa'da yaşıyor."), mem.list().map { it.text })
        assertEquals(listOf("{\"host\":\"huggingface.co\"}", "{\"host\":\"github.com\"}"), again.read("network-log.jsonl")!!.trim().lines())

        // Another key (another user / a copied folder) reads nothing; a tampered record is dropped.
        val stranger = EncryptedFiles(DesktopFiles(File(dir, "app")), DesktopCipher(File(dir, "other.key")))
        assertEquals("", stranger.read("memory.json"))
        val log = File(dir, "app/network-log.jsonl")
        val lines = log.readLines()
        log.writeText(lines[0].dropLast(4) + "AAAA\n" + lines[1] + "\n")
        assertEquals(listOf("{\"host\":\"github.com\"}"), again.read("network-log.jsonl")!!.trim().lines())

        // Files from before encryption are still read, and sealed on their next write.
        File(dir, "app/settings.json").writeText("{\"userName\":\"Ahmet\"}")
        assertEquals("{\"userName\":\"Ahmet\"}", again.read("settings.json"))
        again.write("settings.json", "{\"userName\":\"Ahmet\"}")
        assertFalse("Ahmet" in File(dir, "app/settings.json").readText())
        assertNull(again.read("missing.json"))

        if (windows) {
            // The key on disk is DPAPI-sealed, not the raw 32 bytes.
            assertTrue(keyFile.length() > 100, "DPAPI blob expected, got ${keyFile.length()} bytes")
            println("DPAPI key blob: ${keyFile.length()} bytes")
        }
    }
}
