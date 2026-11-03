package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.desktop.kapi.DesktopKapi
import io.github.ahmetdurmus1218.jarvis.ui.ImportOutcome
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/** How deep the model search looks into each folder. */
private const val SEARCH_DEPTH = 5

/**
 * Where model files may already be on this PC: the folder of the version before the rename, the
 * user's Downloads / Desktop / Documents, and the model folders of Hugging Face, LM Studio and Ollama.
 */
fun defaultModelSearchRoots(): List<File> {
    val home = File(System.getProperty("user.home"))
    val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
    return listOfNotNull(
        local?.let { File(it, "Jarvis") },
        File(home, "Downloads"), File(home, "İndirilenler"), File(home, "Desktop"), File(home, "Masaüstü"),
        File(home, "Documents"), File(home, "Belgeler"),
        File(home, ".cache/huggingface/hub"), File(home, ".lmstudio/models"), File(home, ".cache/lm-studio/models"),
        File(home, ".ollama/models/blobs"),
    )
}

/**
 * %LOCALAPPDATA%\Rick on Windows, ~/.local/share/Rick elsewhere. Versions before the rename kept
 * everything (models, memory, settings) in "...\Jarvis": that folder is moved over once, so an
 * update never asks for the models again.
 */
fun dataDir(): File {
    val local = System.getenv("LOCALAPPDATA")
    val base = if (!local.isNullOrBlank()) File(local) else File(System.getProperty("user.home"), ".local/share")
    return dataDirIn(base)
}

internal fun dataDirIn(base: File): File {
    val dir = File(base, "Rick")
    val legacy = File(base, "Jarvis")
    if (!dir.exists() && legacy.isDirectory) {
        // Same volume: a rename, instant even for gigabytes of models.
        if (!legacy.renameTo(dir)) runCatching { legacy.copyRecursively(dir, overwrite = false) }
    }
    return dir.apply { mkdirs() }
}

class DesktopModelStorage(
    private val dir: File,
    private val partialDir: File,
    /** Where model files may already be: see [defaultModelSearchRoots]. */
    private val searchRoots: () -> List<File> = ::defaultModelSearchRoots,
) : ModelStorage {
    private val unverifiedDir = File(dir, "dogrulanmamis")
    /** Folders the user added with "Klasörde bul", one path per line. */
    private val foldersFile = File(dir, "arama-klasorleri.txt")

    init { dir.mkdirs() }

    private fun file(item: LockedItem) = File(dir, item.fileName)
    private fun marker(item: LockedItem) = File(dir, item.fileName + ".verified")

    override fun isInstalled(item: LockedItem): Boolean {
        val f = file(item)
        val m = marker(item)
        return f.isFile && f.length() == item.size && m.isFile && m.readText().trim().equals(item.sha256, ignoreCase = true)
    }

    override fun pathOf(item: LockedItem): String = file(item).absolutePath

    override fun commit(item: LockedItem, tempPath: String) {
        val src = File(tempPath)
        val dst = file(item)
        dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        marker(item).writeText(item.sha256)
    }

    override fun delete(item: LockedItem) {
        file(item).delete()
        marker(item).delete()
    }

    override fun deleteTemp(tempPath: String) {
        File(tempPath).delete()
    }

    override fun importedUnverifiedFiles(): List<String> = unverifiedDir.listFiles()?.map { it.name }.orEmpty().sorted()

    override fun keepUnverified(tempPath: String, fileName: String) {
        unverifiedDir.mkdirs()
        val dst = File(unverifiedDir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val src = File(tempPath)
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    override suspend fun pickSearchFolder(): Boolean {
        val chosen: File? = suspendCancellableCoroutine { cont ->
            SwingUtilities.invokeLater {
                val chooser = javax.swing.JFileChooser().apply {
                    dialogTitle = "Model dosyalarının olduğu klasörü seçin"
                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                }
                val f = if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
                if (cont.isActive) cont.resume(f)
            }
        }
        if (chosen == null || !chosen.isDirectory) return false
        addSearchFolder(chosen)
        return true
    }

    fun addSearchFolder(folder: File) {
        if (savedFolders().none { it.absolutePath == folder.absolutePath }) foldersFile.appendText(folder.absolutePath + "\n")
    }

    private fun savedFolders(): List<File> =
        foldersFile.takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }.map(::File)

    override suspend fun adoptExisting(items: List<LockedItem>, onCandidate: (LockedItem) -> Unit): Set<String> = withContext(Dispatchers.IO) {
        val wanted = items.filterNot(::isInstalled)
        if (wanted.isEmpty()) return@withContext emptySet()
        val own = dir.canonicalFile
        val adopted = mutableSetOf<String>()
        val roots = (savedFolders() + searchRoots()).filter { it.isDirectory }.distinctBy { it.canonicalPath }
        for (root in roots) {
            val left = wanted.filter { it.id !in adopted }
            if (left.isEmpty()) break
            val files = root.walkTopDown()
                .onEnter { d -> d == root || (!d.name.startsWith(".") && !d.canonicalFile.startsWith(own)) }
                .maxDepth(SEARCH_DEPTH)
                .filter { it.isFile }
            for (f in files) {
                val item = left.firstOrNull { it.id !in adopted && matches(it, f) } ?: continue
                onCandidate(item)
                if (runCatching { adopt(item, f) }.getOrDefault(false)) adopted += item.id
            }
        }
        adopted
    }

    /** Same name and size (other programs keep the original file name; Ollama names blobs by SHA-256). */
    private fun matches(item: LockedItem, f: File): Boolean =
        (f.name.equals(item.fileName, ignoreCase = true) || f.name.equals("sha256-" + item.sha256, ignoreCase = true)) &&
            f.length() == item.size

    /** Hash-checks [found] and puts it in place: a hard link (no extra space) on the same disk, else a copy. */
    private fun adopt(item: LockedItem, found: File): Boolean {
        val real = runCatching { found.toPath().toRealPath().toFile() }.getOrDefault(found)
        val (sha, size) = DesktopKapi.sha256(real)
        if (!sha.equals(item.sha256, ignoreCase = true) || size != item.size) return false
        partialDir.mkdirs()
        val tmp = File(partialDir, "found-" + System.nanoTime())
        val linked = runCatching { java.nio.file.Files.createLink(tmp.toPath(), real.toPath()) }.isSuccess
        if (!linked) {
            real.copyTo(tmp, overwrite = true)
            // A copy is a new file: check it once more before trusting it.
            if (!DesktopKapi.sha256(tmp).first.equals(item.sha256, ignoreCase = true)) {
                tmp.delete()
                return false
            }
        }
        commit(item, tmp.absolutePath)
        return true
    }

    override suspend fun pickAndImport(): ImportOutcome {
        val chosen: File = suspendCancellableCoroutine { cont ->
            SwingUtilities.invokeLater {
                val dialog = FileDialog(null as Frame?, "Model dosyası seçin", FileDialog.LOAD)
                dialog.isVisible = true
                val f = dialog.files.firstOrNull()
                if (cont.isActive) cont.resume(f ?: File(""))
            }
        }
        if (chosen.path.isEmpty()) return ImportOutcome.Cancelled
        return withContext(Dispatchers.IO) {
            try {
                partialDir.mkdirs()
                val tmp = File(partialDir, "import-" + System.nanoTime())
                chosen.copyTo(tmp, overwrite = true)
                val (sha, size) = DesktopKapi.sha256(tmp)
                ImportOutcome.Imported(tmp.absolutePath, chosen.name, sha, size)
            } catch (e: Exception) {
                ImportOutcome.Failed(e.message ?: "İçe aktarma başarısız")
            }
        }
    }
}
