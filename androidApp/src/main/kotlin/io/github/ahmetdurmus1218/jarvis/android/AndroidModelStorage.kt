package io.github.ahmetdurmus1218.jarvis.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.ui.ImportOutcome
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object Hashing {
    /** Returns (sha256 hex, byte count). */
    fun sha256(file: File): Pair<String, Long> = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
        }
        md.digest().joinToString("") { "%02x".format(it) } to total
    }
}

/**
 * Model files live in Kale's private storage: files/models/<file>. A sibling "<file>.verified"
 * holds the SHA-256 that matched models.lock.json; without it the file does not count as installed.
 */
class AndroidModelStorage(
    private val context: Context,
    /** Folder picker for "Klasörde bul"; the chosen folder is remembered (persisted read permission). */
    private val folderPicker: suspend () -> Uri? = { null },
    private val picker: suspend () -> Uri?,
) : ModelStorage {
    private val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    private val partialDir: File get() = File(dir, ".partial").apply { mkdirs() }
    private val unverifiedDir: File get() = File(dir, "dogrulanmamis").apply { mkdirs() }

    fun partialFile(item: LockedItem) = File(partialDir, item.sha256 + ".part")

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
        partialFile(item).delete()
    }

    override fun deleteTemp(tempPath: String) {
        File(tempPath).delete()
    }

    override fun importedUnverifiedFiles(): List<String> = unverifiedDir.listFiles()?.map { it.name }.orEmpty().sorted()

    override fun keepUnverified(tempPath: String, fileName: String) {
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val src = File(tempPath)
        val dst = File(unverifiedDir, safe)
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    override suspend fun pickSearchFolder(): Boolean {
        val tree = folderPicker() ?: return false
        return runCatching {
            context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
    }

    private data class Found(val uri: Uri, val name: String, val size: Long)

    /** Files under a granted folder, a few levels deep, without any storage permission (SAF). */
    private fun listTree(tree: Uri): List<Found> {
        val resolver = context.contentResolver
        val out = mutableListOf<Found>()
        fun walk(docId: String, depth: Int) {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(3) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < SEARCH_DEPTH && !name.startsWith(".")) walk(id, depth + 1)
                    } else {
                        out += Found(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id), name, if (c.isNull(2)) -1 else c.getLong(2))
                    }
                }
            }
        }
        walk(android.provider.DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    override suspend fun adoptExisting(items: List<LockedItem>, onCandidate: (LockedItem) -> Unit): Set<String> = withContext(Dispatchers.IO) {
        val wanted = items.filterNot(::isInstalled)
        val trees = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        if (wanted.isEmpty() || trees.isEmpty()) return@withContext emptySet()
        val adopted = mutableSetOf<String>()
        for (tree in trees) {
            val files = runCatching { listTree(tree) }.getOrDefault(emptyList())
            for (item in wanted) {
                if (item.id in adopted) continue
                val f = files.firstOrNull { it.name.equals(item.fileName, ignoreCase = true) && it.size == item.size } ?: continue
                onCandidate(item)
                if (runCatching { copyVerified(item, f.uri) }.getOrDefault(false)) adopted += item.id
            }
        }
        adopted
    }

    /** Copies [uri] into place while hashing it; kept only if the SHA-256 matches the lock. */
    private fun copyVerified(item: LockedItem, uri: Uri): Boolean {
        val tmp = File(partialDir, "found-" + System.nanoTime())
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Dosya açılamadı" }
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    total += n
                }
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(item.sha256, ignoreCase = true) || total != item.size) {
            tmp.delete()
            return false
        }
        commit(item, tmp.absolutePath)
        return true
    }

    override suspend fun pickAndImport(): ImportOutcome {
        val uri = picker() ?: return ImportOutcome.Cancelled
        return withContext(Dispatchers.IO) {
            try {
                val name = displayName(uri) ?: "dosya"
                val tmp = File(partialDir, "import-" + System.nanoTime())
                val md = MessageDigest.getInstance("SHA-256")
                var total = 0L
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Dosya açılamadı" }
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                }
                ImportOutcome.Imported(tmp.absolutePath, name, md.digest().joinToString("") { "%02x".format(it) }, total)
            } catch (e: Exception) {
                ImportOutcome.Failed(e.message ?: "İçe aktarma başarısız")
            }
        }
    }

    private companion object {
        const val SEARCH_DEPTH = 3
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
