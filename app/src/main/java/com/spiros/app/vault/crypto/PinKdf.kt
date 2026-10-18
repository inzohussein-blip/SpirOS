package com.spiros.app.vault.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Stretches a PIN into a 256-bit key so each guess costs real CPU time. */
internal object PinKdf {
    const val ITERATIONS = 200_000

    fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
