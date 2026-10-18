package com.spiros.app.vault

import android.util.Base64
import org.json.JSONObject

/**
 * Everything the vault persists outside the encrypted data: only wrapped keys,
 * salts and attempt counters. Nothing here reveals the PIN or the data key.
 */
internal data class VaultState(
    val iterations: Int,
    val pinSalt: ByteArray,
    /** deviceKey( pinKey( dataKey ) ) */
    val pinWrapped: ByteArray,
    /** biometricKey( dataKey ), IV-prefixed */
    val bioWrapped: ByteArray? = null,
    val panicSalt: ByteArray? = null,
    /** deviceKey( PBKDF2(panicPin) ) */
    val panicHash: ByteArray? = null,
    val failedAttempts: Int = 0,
    val lockoutUntil: Long = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        put("iterations", iterations)
        put("pinSalt", pinSalt.b64())
        put("pinWrapped", pinWrapped.b64())
        bioWrapped?.let { put("bioWrapped", it.b64()) }
        panicSalt?.let { put("panicSalt", it.b64()) }
        panicHash?.let { put("panicHash", it.b64()) }
        put("failedAttempts", failedAttempts)
        put("lockoutUntil", lockoutUntil)
    }.toString()

    companion object {
        fun fromJson(json: String): VaultState {
            val o = JSONObject(json)
            return VaultState(
                iterations = o.getInt("iterations"),
                pinSalt = o.bytes("pinSalt")!!,
                pinWrapped = o.bytes("pinWrapped")!!,
                bioWrapped = o.bytes("bioWrapped"),
                panicSalt = o.bytes("panicSalt"),
                panicHash = o.bytes("panicHash"),
                failedAttempts = o.optInt("failedAttempts"),
                lockoutUntil = o.optLong("lockoutUntil"),
            )
        }

        private fun JSONObject.bytes(name: String): ByteArray? =
            if (has(name)) Base64.decode(getString(name), Base64.NO_WRAP) else null

        private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    }
}
