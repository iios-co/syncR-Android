package com.syncr.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts SMB credentials using AES-256-GCM backed by
 * the Android KeyStore. The raw password is never written to disk.
 *
 * Encrypted blobs are stored as Base64 files in [storageDir]:
 *   cred_<alias>.enc  →  12-byte IV || ciphertext
 *
 * Usage:
 *   manager.storePassword("smb_password", "s3cr3t")
 *   val pw = manager.retrievePassword("smb_password")
 */
class CredentialManager(private val storageDir: File) {

    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }

    private fun ensureKey(alias: String) {
        if (keyStore.containsAlias(alias)) return
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private fun getKey(alias: String): SecretKey =
        (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey

    /** Encrypt [password] under [alias] and persist to disk. */
    fun storePassword(alias: String, password: String) {
        ensureKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(alias))
        val iv = cipher.iv                                    // 12 bytes (GCM)
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        credFile(alias).writeText(blob)
        Log.d(TAG, "Stored credential: $alias")
    }

    /**
     * Decrypt and return the password for [alias].
     * Returns null if the credential file doesn't exist or decryption fails
     * (e.g., KeyStore unavailable before first unlock after boot).
     */
    fun retrievePassword(alias: String): String? {
        val file = credFile(alias)
        if (!file.exists()) return null
        if (!keyStore.containsAlias(alias)) return null
        return try {
            val combined = Base64.decode(file.readText(), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_SIZE)
            val ciphertext = combined.copyOfRange(GCM_IV_SIZE, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve credential '$alias': ${e.message}")
            null
        }
    }

    fun deleteCredential(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        credFile(alias).delete()
    }

    private fun credFile(alias: String): File {
        val safe = alias.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(storageDir, "cred_$safe.enc")
    }

    companion object {
        private const val TAG = "CredentialManager"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
    }
}
