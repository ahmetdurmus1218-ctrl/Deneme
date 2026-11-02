package io.github.ahmetdurmus1218.jarvis.core.net

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.Platform

/**
 * The rules every download must pass (section 3.2), shared by the Android gate and the desktop
 * "jarvis-kapi". This file only checks strings; it contains no networking code.
 */
object DownloadPolicy {

    /** Exact hosts and "*.suffix" wildcards. */
    val ALLOWED_HOSTS: List<String> = listOf(
        "huggingface.co", "*.huggingface.co", "hf.co", "*.hf.co",
        "download.kiwix.org", "*.download.kiwix.org",
        "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
    )

    val ALLOWED_EXTENSIONS: List<String> = listOf(".litertlm", ".onnx", ".gguf", ".zim", ".tar.bz2", ".json", ".txt")

    /** Only for pinned desktop binaries (llama-server, kiwix-serve), never on Android. */
    val DESKTOP_BINARY_EXTENSIONS: List<String> = listOf(".zip")

    val FORBIDDEN_EXTENSIONS: List<String> = listOf(
        ".apk", ".aab", ".dex", ".so", ".jar", ".exe", ".dll", ".msi", ".bat", ".cmd", ".ps1", ".sh", ".com", ".scr",
    )

    /** Hard upper bound for any single file (largest locked model is ~6 GB). */
    const val MAX_BYTES: Long = 16L * 1024 * 1024 * 1024

    /** Max redirect hops; every hop must pass [checkUrl] as well. */
    const val MAX_REDIRECTS = 5

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Denied(val reason: String) : Verdict
    }

    fun hostAllowed(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return ALLOWED_HOSTS.any { pattern ->
            if (pattern.startsWith("*.")) {
                val suffix = pattern.substring(1) // ".hf.co"
                h.endsWith(suffix) && h.length > suffix.length && !h.removeSuffix(suffix).contains('/')
            } else {
                h == pattern
            }
        }
    }

    data class ParsedUrl(val scheme: String, val host: String, val port: Int?, val path: String)

    /** Minimal, strict URL parser: scheme://host[:port]/path?query (no userinfo allowed). */
    fun parse(url: String): ParsedUrl? {
        val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$").matchEntire(url.trim()) ?: return null
        val authority = m.groupValues[2]
        if (authority.isEmpty() || authority.contains('@')) return null
        val host: String
        val port: Int?
        if (authority.contains(':')) {
            host = authority.substringBefore(':')
            port = authority.substringAfter(':').toIntOrNull() ?: return null
        } else {
            host = authority
            port = null
        }
        if (!Regex("^[a-zA-Z0-9.-]+$").matches(host)) return null
        return ParsedUrl(m.groupValues[1].lowercase(), host.lowercase(), port, m.groupValues[3])
    }

    /** Checks a URL (initial request or redirect target). */
    fun checkUrl(url: String): Verdict {
        val u = parse(url) ?: return Verdict.Denied("Geçersiz adres")
        if (u.scheme != "https") return Verdict.Denied("Yalnızca HTTPS izinli")
        if (u.port != null && u.port != 443) return Verdict.Denied("Standart dışı port")
        if (!hostAllowed(u.host)) return Verdict.Denied("İzin listesinde olmayan alan adı: ${u.host}")
        return Verdict.Allowed
    }

    fun checkFileName(fileName: String, platform: Platform, kind: String): Verdict {
        val n = fileName.lowercase()
        if (n.contains('/') || n.contains('\\') || n.startsWith(".")) return Verdict.Denied("Geçersiz dosya adı")
        if (FORBIDDEN_EXTENSIONS.any { n.endsWith(it) }) return Verdict.Denied("Yasaklı dosya türü")
        if (ALLOWED_EXTENSIONS.any { n.endsWith(it) }) return Verdict.Allowed
        if (platform == Platform.DESKTOP && kind == "binary" && DESKTOP_BINARY_EXTENSIONS.any { n.endsWith(it) }) return Verdict.Allowed
        return Verdict.Denied("İzin verilmeyen dosya türü")
    }

    /** Full pre-flight check of a locked item before any network request. */
    fun check(item: LockedItem, platform: Platform, urlCheck: (String) -> Verdict = ::checkUrl): Verdict {
        if (platform.key !in item.platforms) return Verdict.Denied("Bu öğe bu platform için değil")
        urlCheck(item.url).let { if (it is Verdict.Denied) return it }
        checkFileName(item.fileName, platform, item.kind).let { if (it is Verdict.Denied) return it }
        if (item.size <= 0 || item.size > MAX_BYTES) return Verdict.Denied("Boyut sınırı aşıldı")
        if (!isSha256(item.sha256)) return Verdict.Denied("Geçersiz SHA-256")
        return Verdict.Allowed
    }

    fun isSha256(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** Bytes the server may send for [item]: the exact locked size. */
    fun maxBytesFor(item: LockedItem): Long = item.size
}
