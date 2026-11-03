package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.ui.FileCipher
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for the PC's data. The random key is stored next to the data, sealed with Windows
 * DPAPI (current user), so the files are unreadable for other accounts and when copied elsewhere.
 * Outside Windows (development) the key file is readable by its owner only.
 */
class DesktopCipher(private val keyFile: File, private val dpapi: Dpapi? = Dpapi.forThisOs()) : FileCipher {
    override val label: String get() = if (dpapi != null) "AES-256-GCM · Windows DPAPI" else "AES-256-GCM · yerel anahtar dosyası"

    private val key: SecretKeySpec by lazy { SecretKeySpec(loadOrCreate(), "AES") }

    private fun loadOrCreate(): ByteArray {
        if (keyFile.isFile) {
            val stored = keyFile.readBytes()
            return dpapi?.unprotect(stored) ?: stored
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(dpapi?.protect(raw) ?: raw)
        runCatching { Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
        return raw
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/** Windows DPAPI (CurrentUser) through PowerShell's ProtectedData; the bytes go through stdin/stdout as Base64. */
class Dpapi {
    fun protect(data: ByteArray): ByteArray = run("Protect", data)
    fun unprotect(data: ByteArray): ByteArray = run("Unprotect", data)

    private fun run(op: String, data: ByteArray): ByteArray {
        val script = "Add-Type -AssemblyName System.Security; " +
            "\$b = [Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); " +
            "[Console]::Out.Write([Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$op(\$b, \$null, 'CurrentUser')))"
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script).start()
        p.outputStream.use { it.write(Base64.getEncoder().encode(data)) }
        val out = p.inputStream.readBytes().decodeToString().trim()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && out.isNotEmpty()) { "DPAPI $op failed: " + p.errorStream.readBytes().decodeToString().take(300) }
        return Base64.getDecoder().decode(out)
    }

    companion object {
        fun forThisOs(): Dpapi? = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Dpapi() else null
    }
}
