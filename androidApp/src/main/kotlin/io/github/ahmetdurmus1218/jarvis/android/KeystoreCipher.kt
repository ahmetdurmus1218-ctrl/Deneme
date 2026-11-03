package io.github.ahmetdurmus1218.jarvis.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.ahmetdurmus1218.jarvis.ui.FileCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key created inside the Android Keystore (hardware-backed where the phone has
 * it): the key cannot be read or exported, not even by Rick. Record = 12-byte IV + ciphertext.
 */
class KeystoreCipher : FileCipher {
    override val label = "AES-256-GCM · Android Keystore"

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "jarvis-data-v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
