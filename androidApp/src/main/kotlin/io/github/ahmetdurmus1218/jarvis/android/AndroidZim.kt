package io.github.ahmetdurmus1218.jarvis.android

import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiHit
import io.github.ahmetdurmus1218.jarvis.core.knowledge.ZimBackend
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher

/**
 * Offline Wikipedia on the phone: opens the installed, hash-verified ZIM with libzim (java-libkiwix)
 * and searches titles and full text on the device. Only the ZIM reader's native libraries are
 * loaded (the Kiwix server part is not packaged).
 */
class AndroidZim(private val zimPath: () -> String?) : ZimBackend {
    private val lock = Mutex()
    private var openedPath: String? = null
    private var archive: Archive? = null
    private var searcher: Searcher? = null
    private var suggester: SuggestionSearcher? = null

    override fun isReady(): Boolean = zimPath() != null

    private suspend fun <T> withArchive(block: (Archive) -> T): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            val path = zimPath() ?: return@withLock null
            if (openedPath != path) {
                closeLocked()
                loadNative()
                archive = Archive(path)
                openedPath = path
            }
            block(archive!!)
        }
    }

    override suspend fun titleSearch(query: String, limit: Int): List<WikiHit> = withArchive { a ->
        if (!a.hasTitleIndex()) return@withArchive emptyList()
        val s = (suggester ?: SuggestionSearcher(a).also { suggester = it }).suggest(query)
        val it = s.getResults(0, limit)
        buildList {
            while (it.hasNext()) {
                val item = it.next()
                add(WikiHit(item.title, item.path, if (item.hasSnippet()) item.snippet else ""))
            }
        }
    }.orEmpty()

    override suspend fun fullTextSearch(query: String, limit: Int): List<WikiHit> = withArchive { a ->
        if (!a.hasFulltextIndex()) return@withArchive emptyList()
        val search = (searcher ?: Searcher(a).also { searcher = it }).search(Query(query))
        val it = search.getResults(0, limit)
        buildList {
            while (it.hasNext()) {
                // The iterator points at the entry next() will return: read its fields first.
                val title = it.title
                val path = it.path
                val snippet = runCatching { it.snippet }.getOrDefault("")
                it.next()
                add(WikiHit(title, path, snippet.replace(Regex("<[^>]+>"), "")))
            }
        }
    }.orEmpty()

    override suspend fun article(path: String): Pair<String, String>? = withArchive { a ->
        if (!a.hasEntryByPath(path)) return@withArchive null
        val entry = a.getEntryByPath(path)
        val item = entry.getItem(true) // follows redirects
        if (!item.mimetype.startsWith("text/html")) return@withArchive null
        String(item.data.data, Charsets.UTF_8) to item.title.ifBlank { entry.title }
    }

    private fun closeLocked() {
        runCatching { searcher?.dispose() }
        runCatching { suggester?.dispose() }
        runCatching { archive?.dispose() }
        searcher = null
        suggester = null
        archive = null
        openedPath = null
    }

    companion object {
        @Volatile private var loaded = false

        /** libzim and its JNI wrapper only; libkiwix (server, library management) is not needed. */
        @Synchronized
        fun loadNative() {
            if (loaded) return
            System.loadLibrary("c++_shared")
            System.loadLibrary("zim")
            System.loadLibrary("zim_wrapper")
            loaded = true
        }

        /** Path of the installed, verified Turkish Wikipedia ZIM, if any. */
        fun installedZim(storage: AndroidModelStorage, registry: ModelRegistry): String? =
            registry.forPlatform(Platform.ANDROID).filter { it.kind == "zim" }.firstOrNull { storage.isInstalled(it) }?.let(storage::pathOf)
    }
}
