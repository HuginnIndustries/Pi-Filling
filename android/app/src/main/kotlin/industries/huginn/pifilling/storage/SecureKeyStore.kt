package industries.huginn.pifilling.storage

import android.content.Context
import android.content.SharedPreferences
import industries.huginn.pifilling.sandbox.AgentProvider
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's Anthropic API key encrypted at rest, app-private.
 *
 * Design choice: rather than depend on the deprecated androidx.security-crypto
 * (or a third-party fork as Kai does), we use the AndroidKeyStore directly. A
 * hardware-bound AES-256-GCM key (non-exportable) encrypts the secret; only the
 * ciphertext + IV land in SharedPreferences. The Keystore key cannot leave the
 * device and does not survive backup/restore, which is why Auto Backup is
 * disabled for this prefs file (see backup_rules.xml).
 *
 * The key is only ever handed to the wrapper in-memory at process spawn (env),
 * per ARCHITECTURE.md — it is never written to the sandbox filesystem.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Credentials are stored per provider: switching provider must not silently
    // reuse the previous one's key, and each is encrypted under the same
    // AndroidKeyStore master key.

    fun hasApiKey(provider: AgentProvider = AgentProvider.DEFAULT): Boolean =
        prefs.contains(provider.prefKey)

    fun setApiKey(apiKey: String, provider: AgentProvider = AgentProvider.DEFAULT) {
        val (iv, ciphertext) = encrypt(apiKey.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(provider.prefKey, encode(iv, ciphertext))
            .apply()
    }

    /** Returns the decrypted key, or null if none is stored / decryption fails. */
    fun getApiKey(provider: AgentProvider = AgentProvider.DEFAULT): String? {
        val stored = prefs.getString(provider.prefKey, null) ?: return null
        return try {
            val (iv, ciphertext) = decode(stored)
            String(decrypt(iv, ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // Tampered/rotated key material — drop it so the user can re-enter.
            clearApiKey(provider)
            null
        }
    }

    fun clearApiKey(provider: AgentProvider = AgentProvider.DEFAULT) {
        prefs.edit().remove(provider.prefKey).apply()
    }

    // ---- crypto ----

    private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv to ciphertext
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        Base64.encodeToString(iv, Base64.NO_WRAP) + DELIM + Base64.encodeToString(ciphertext, Base64.NO_WRAP)

    private fun decode(stored: String): Pair<ByteArray, ByteArray> {
        val parts = stored.split(DELIM)
        require(parts.size == 2) { "malformed stored key" }
        return Base64.decode(parts[0], Base64.NO_WRAP) to Base64.decode(parts[1], Base64.NO_WRAP)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pifilling_master"
        const val PREFS_NAME = "pifilling_secure_prefs"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val DELIM = ":"
    }
}
