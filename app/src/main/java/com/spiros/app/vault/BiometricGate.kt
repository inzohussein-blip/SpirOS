package com.spiros.app.vault

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher

/** Thin wrapper over the platform BiometricPrompt (no AndroidX needed on API 34+). */
object BiometricGate {

    fun isAvailable(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        activity: Activity,
        cipher: Cipher,
        title: String,
        negativeText: String,
        onSuccess: (Cipher) -> Unit,
        onFailure: () -> Unit = {},
    ) {
        val executor = activity.mainExecutor
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButton(negativeText, executor) { _, _ -> onFailure() }
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated != null) onSuccess(authenticated) else onFailure()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure()
            },
        )
    }
}
