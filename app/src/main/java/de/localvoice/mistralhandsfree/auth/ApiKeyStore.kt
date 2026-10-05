package de.localvoice.mistralhandsfree.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the Mistral API key lives. */
interface ApiKeyStore {

    /** true while a key is stored. */
    val signedIn: StateFlow<Boolean>

    /** The key, or null if none is stored (or it can no longer be read). */
    fun load(): String?

    /** @throws Exception if the key cannot be stored securely. It is never stored in the clear. */
    fun save(key: String)

    fun clear()
}

/**
 * Keeps the key encrypted with an AES-256 key that never leaves the Android
 * Keystore, in the hardware-backed secure element where the device has one.
 *
 * What sits in the preferences file is only ciphertext. The file is excluded
 * from backups (the manifest disables them), which matters twice: the Keystore
 * key does not travel with a backup, so a restored ciphertext would be useless,
 * and a credential has no business in a cloud backup anyway.
 *
 * If decryption fails - for example because the screen lock was removed and the
 * Keystore wiped its keys - the stored value is dropped and the user is asked to
 * sign in again, rather than the app crashing.
 */
class KeystoreApiKeyStore(context: Context) : ApiKeyStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _signedIn = MutableStateFlow(prefs.contains(KEY_BLOB))
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** Decrypting goes through the Keystore; do it once, not on every request. */
    @Volatile
    private var cached: String? = null

    override fun load(): String? {
        cached?.let { return it }
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        val key = try {
            decrypt(blob)
        } catch (_: Exception) {
            null
        }
        if (key == null) {
            clear()
            return null
        }
        cached = key
        return key
    }

    override fun save(key: String) {
        val blob = encrypt(key) // throws before anything is written if encryption fails
        prefs.edit().putString(KEY_BLOB, blob).apply()
        cached = key
        _signedIn.value = true
    }

    override fun clear() {
        cached = null
        prefs.edit().remove(KEY_BLOB).apply()
        _signedIn.value = false
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? {
        val parts = blob.split(SEPARATOR)
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

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

    private companion object {
        const val PREFS = "credentials"
        const val KEY_BLOB = "mistral_api_key"
        const val KEY_ALIAS = "handsfree_for_mistral_api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
