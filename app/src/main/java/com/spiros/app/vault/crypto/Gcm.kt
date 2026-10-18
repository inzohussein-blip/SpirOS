package com.spiros.app.vault.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM for small blobs. Output layout: 12-byte IV || ciphertext+tag. */
internal object Gcm {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_SIZE = 12
    const val TAG_BITS = 128

    private val random = SecureRandom()

    fun encrypt(key: SecretKey, plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        if (key is SecretKeySpec) {
            val iv = ByteArray(IV_SIZE).also(random::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        } else {
            // Keystore keys refuse caller-chosen IVs and generate their own.
            cipher.init(Cipher.ENCRYPT_MODE, key)
        }
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }

    /** Throws [javax.crypto.AEADBadTagException] if the key is wrong or data was tampered with. */
    fun decrypt(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
