package com.spiros.app.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Hardware-backed keys in the Android Keystore. Key material never leaves the
 * TEE/StrongBox, so a copy of the app's files is useless on another device.
 */
internal object KeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"
    private const val DEVICE_KEY = "spiros.vault.device"
    private const val BIOMETRIC_KEY = "spiros.vault.biometric"

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Binds vault data to this device; usable without user authentication. */
    fun deviceKey(): SecretKey =
        keyStore().getKey(DEVICE_KEY, null) as SecretKey? ?: generate(DEVICE_KEY, biometric = false)

    /** Usable only right after a strong biometric match; invalidated if fingerprints change. */
    fun biometricKey(): SecretKey? = keyStore().getKey(BIOMETRIC_KEY, null) as SecretKey?

    fun createBiometricKey(): SecretKey {
        deleteBiometricKey()
        return generate(BIOMETRIC_KEY, biometric = true)
    }

    fun deleteBiometricKey() {
        runCatching { keyStore().deleteEntry(BIOMETRIC_KEY) }
    }

    fun deleteAll() {
        val ks = keyStore()
        listOf(DEVICE_KEY, BIOMETRIC_KEY).forEach { runCatching { ks.deleteEntry(it) } }
    }

    /** One of KeyProperties.SECURITY_LEVEL_*, describing where the device key lives. */
    fun deviceKeySecurityLevel(): Int {
        val key = deviceKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return info.securityLevel
    }

    private fun generate(alias: String, biometric: Boolean): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .apply {
                if (biometric) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        // Prefer a dedicated secure chip; many devices only have a TEE, which is fine.
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (e: Exception) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }
}
