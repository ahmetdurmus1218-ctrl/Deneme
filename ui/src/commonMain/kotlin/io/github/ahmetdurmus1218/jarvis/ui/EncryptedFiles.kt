package io.github.ahmetdurmus1218.jarvis.ui

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Authenticated encryption of the app's own data (AES-GCM; the key never leaves the platform's key store). */
interface FileCipher {
    /** Short description for Settings, e.g. "AES-256-GCM · Android Keystore". */
    val label: String
    fun encrypt(plain: ByteArray): ByteArray
    /** Throws if the data was altered or encrypted with another key. */
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * The app's files, encrypted at rest: memory, documents index, conversation, settings and the
 * network log. A whole-file write is one sealed record; an appended line is its own record, so the
 * network log can still grow line by line. Files written before encryption was on are read as they
 * are and sealed on their next write. A record that fails to decrypt (tampered) is dropped.
 */
@OptIn(ExperimentalEncodingApi::class)
class EncryptedFiles(private val inner: AppFiles, private val cipher: FileCipher) : AppFiles {
    private fun seal(text: String) = PREFIX + Base64.encode(cipher.encrypt(text.encodeToByteArray()))

    private fun open(record: String): String? =
        if (!record.startsWith(PREFIX)) record
        else runCatching { cipher.decrypt(Base64.decode(record.removePrefix(PREFIX))).decodeToString() }.getOrNull()

    override fun read(name: String): String? {
        val raw = inner.read(name) ?: return null
        if (!raw.contains(PREFIX)) return raw
        return raw.lineSequence().filter { it.isNotEmpty() }.mapNotNull(::open).joinToString("\n")
            .let { if (raw.endsWith("\n")) it + "\n" else it }
    }

    override fun write(name: String, text: String) = inner.write(name, seal(text))

    override fun append(name: String, line: String) = inner.append(name, seal(line))

    companion object {
        const val PREFIX = "JENC1:"
    }
}
