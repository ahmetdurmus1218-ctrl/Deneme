package io.github.ahmetdurmus1218.jarvis.desktop.kapi

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.ui.DownloadOutcome
import io.github.ahmetdurmus1218.jarvis.ui.DownloadProgress
import io.github.ahmetdurmus1218.jarvis.ui.DownloadState
import io.github.ahmetdurmus1218.jarvis.ui.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/**
 * Entry point of the separate `rick-kapi.exe` launcher: the only Rick program the firewall ever
 * lets out. It accepts a lock-file item id (never a URL), downloads that item with all the gate
 * rules (allowlists, pinning, size limit, resume) into the partial folder and reports on stdout:
 *   P <bytes> <total>            progress
 *   DONE <bytes> <sha256> <host> <path>
 *   FAIL <bytes> <message>
 * or, with --query <url> (one approved Wikipedia search, since 1.1):
 *   TEXT <host> <base64 body>
 *   FAIL 0 <message>
 */
fun main(args: Array<String>) {
    val a = args.toList()
    fun arg(name: String) = a.indexOf(name).takeIf { it >= 0 }?.let { a.getOrNull(it + 1) }
    arg("--query")?.let { url ->
        when (val r = DesktopKapi(File(System.getProperty("java.io.tmpdir"))).query(url)) {
            is io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Ok -> {
                println("TEXT ${r.host} " + java.util.Base64.getEncoder().encodeToString(r.body.encodeToByteArray()))
                exitProcess(0)
            }
            is io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed -> { println("FAIL 0 ${r.message.replace('\n', ' ')}"); exitProcess(1) }
        }
    }
    val id = arg("--item")
    val partial = arg("--partial")
    if (id == null || partial == null) {
        println("FAIL 0 kullanım: rick-kapi --item <kilit-id> --partial <klasör>")
        exitProcess(2)
    }
    val item = ModelRegistry.bundled.lock.items.firstOrNull { it.id == id }
    if (item == null) {
        println("FAIL 0 kilit dosyasında olmayan öğe: $id")
        exitProcess(2)
    }
    val outcome = runBlocking {
        DesktopKapi(File(partial)).download(item) { p -> println("P ${p.bytes} ${p.total}"); System.out.flush() }
    }
    when (outcome) {
        is DownloadOutcome.Done -> { println("DONE ${outcome.bytes} ${outcome.sha256} ${outcome.host} ${outcome.path}"); exitProcess(0) }
        is DownloadOutcome.Failed -> { println("FAIL ${outcome.bytes} ${outcome.message.replace('\n', ' ')}"); exitProcess(1) }
    }
}

/**
 * Rick's side on an installed PC: each download runs in rick-kapi.exe (its own firewall rule),
 * progress comes back over stdout, and Rick computes the SHA-256 again itself afterwards.
 */
class KapiProcessDownloader(
    private val kapiExe: File,
    private val partialDir: File,
    /** Called once a download session (one approval) has ended. */
    private val onSessionEnd: suspend (installedPaths: List<String>) -> Unit = {},
) : Downloader {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Ready("rick-kapi.exe (ayrı süreç, güvenlik duvarı kapısı)"))
    override val state: StateFlow<DownloadState> = _state
    @Volatile private var current: Process? = null

    override fun refresh() {
        _state.value = if (kapiExe.isFile) DownloadState.Ready("rick-kapi.exe (ayrı süreç, güvenlik duvarı kapısı)") else DownloadState.Unavailable
    }

    override fun cancel() { current?.destroy() }

    override suspend fun download(item: LockedItem, onProgress: (DownloadProgress) -> Unit): DownloadOutcome = withContext(Dispatchers.IO) {
        val p = try {
            ProcessBuilder(kapiExe.absolutePath, "--item", item.id, "--partial", partialDir.absolutePath).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return@withContext DownloadOutcome.Failed("rick-kapi başlatılamadı: ${e.message}")
        }
        current = p
        p.outputStream.close()
        var result: DownloadOutcome = DownloadOutcome.Failed("rick-kapi sonuç vermedi")
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) result = parseLine(line, onProgress) ?: result
        }
        p.waitFor()
        current = null
        // Never trust the helper's own hash: compute it again here.
        val r = result
        if (r is DownloadOutcome.Done) {
            val f = File(r.path)
            if (!f.isFile || f.parentFile.canonicalPath != partialDir.canonicalPath) return@withContext DownloadOutcome.Failed("rick-kapi beklenmeyen bir yere yazdı")
            val (sha, size) = DesktopKapi.sha256(f)
            return@withContext DownloadOutcome.Done(f.absolutePath, size, sha, r.host)
        }
        r
    }

    override suspend fun endSession(installedPaths: List<String>) = onSessionEnd(installedPaths)

    /** One approved Wikipedia search, run in rick-kapi.exe (the only program the firewall lets out). */
    suspend fun query(url: String): io.github.ahmetdurmus1218.jarvis.ui.WebFetch = withContext(Dispatchers.IO) {
        val p = try {
            ProcessBuilder(kapiExe.absolutePath, "--query", url).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return@withContext io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed("rick-kapi başlatılamadı: ${e.message}")
        }
        p.outputStream.close()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        parseQuery(lines)
    }

    companion object {
        fun parseQuery(lines: List<String>): io.github.ahmetdurmus1218.jarvis.ui.WebFetch {
            for (line in lines) {
                val parts = line.trim().split(' ', limit = 3)
                when (parts.firstOrNull()) {
                    "TEXT" -> if (parts.size == 3) {
                        val body = runCatching { java.util.Base64.getDecoder().decode(parts[2]).decodeToString() }.getOrNull()
                            ?: return io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed("rick-kapi bozuk yanıt verdi")
                        if (body.length > io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.MAX_BYTES) {
                            return io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed("Yanıt boyut sınırını aştı")
                        }
                        return io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Ok(body, parts[1], body.encodeToByteArray().size.toLong())
                    }
                    "FAIL" -> return io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed(line.trim().split(' ', limit = 3).getOrElse(2) { "hata" })
                }
            }
            return io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed("rick-kapi sonuç vermedi")
        }

        fun parseLine(line: String, onProgress: (DownloadProgress) -> Unit): DownloadOutcome? {
            val parts = line.trim().split(' ', limit = 5)
            return when (parts.firstOrNull()) {
                "P" -> {
                    val b = parts.getOrNull(1)?.toLongOrNull()
                    val t = parts.getOrNull(2)?.toLongOrNull()
                    if (b != null && t != null) onProgress(DownloadProgress(b, t))
                    null
                }
                "DONE" -> if (parts.size == 5) DownloadOutcome.Done(parts[4], parts[1].toLongOrNull() ?: 0, parts[2], parts[3]) else null
                "FAIL" -> DownloadOutcome.Failed(line.trim().split(' ', limit = 3).getOrElse(2) { "hata" }, parts.getOrNull(1)?.toLongOrNull() ?: 0)
                else -> null
            }
        }
    }
}
