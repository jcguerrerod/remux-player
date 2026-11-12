package com.player.androidtv.security

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts Jellyfin session credentials at rest using Android Keystore AES-256-GCM.
 */
object TokenVault {

    private const val TAG = "TokenVault"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "remux_player_token_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ENCRYPTED_PREFIX = "enc_v1:"
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.w(TAG, "Keystore unavailable, token cannot be sealed", e)
            null
        }
    }

    fun encrypt(plainToken: String): String {
        if (plainToken.isBlank()) return ""
        val key = getOrCreateKey() ?: return plainToken
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainToken.toByteArray(StandardCharsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            "$ENCRYPTED_PREFIX$ivB64:$dataB64"
        } catch (e: Exception) {
            Log.w(TAG, "Token encryption fallback", e)
            plainToken
        }
    }

    fun decrypt(storedValue: String?): String? {
        if (storedValue.isNullOrBlank()) return null
        if (!storedValue.startsWith(ENCRYPTED_PREFIX)) {
            return storedValue
        }
        val key = getOrCreateKey() ?: return null
        return try {
            val payload = storedValue.removePrefix(ENCRYPTED_PREFIX)
            val parts = payload.split(':', limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Token decryption failed", e)
            null
        }
    }

    /**
     * Reads a token and transparently upgrades any legacy plaintext token in SharedPreferences
     * to AES-GCM encrypted storage so active sessions stay signed in safely.
     */
    fun readAndMigrateToken(prefs: SharedPreferences, key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        if (raw.isBlank()) return null
        if (raw.startsWith(ENCRYPTED_PREFIX)) {
            return decrypt(raw)
        }
        val encrypted = encrypt(raw)
        if (encrypted.startsWith(ENCRYPTED_PREFIX)) {
            prefs.edit().putString(key, encrypted).apply()
        }
        return raw
    }

    fun writeToken(editor: SharedPreferences.Editor, key: String, plainToken: String) {
        editor.putString(key, encrypt(plainToken))
    }
}
