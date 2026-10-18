package com.spiros.app.vault

import android.util.AtomicFile
import com.spiros.app.vault.crypto.Gcm
import com.spiros.app.vault.crypto.KeystoreKeys
import com.spiros.app.vault.crypto.PinKdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The SpirOS vault.
 *
 * A random 256-bit data key encrypts everything. It is stored twice:
 *  - wrapped by a PIN-derived key, then again by a hardware Keystore key
 *    (so brute-forcing the PIN requires this exact phone), and
 *  - optionally wrapped by a biometric-bound Keystore key.
 * While unlocked the data key lives only in memory and is zeroed on [lock].
 */
class Vault(private val root: File) {

    enum class Status { NEW, LOCKED, UNLOCKED }

    sealed interface UnlockResult {
        data object Success : UnlockResult
        data object Wrong : UnlockResult
        data class LockedOut(val remainingMs: Long) : UnlockResult
    }

    private val stateFile = AtomicFile(File(root, "state.json"))
    private var dataKey: ByteArray? = null

    private val _status = MutableStateFlow(if (stateFile.exists()) Status.LOCKED else Status.NEW)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Set while a system picker is in front, so leaving the activity does not lock mid-import. */
    @Volatile
    var holdLock: Boolean = false

    val files = VaultFiles(File(root, "data")) { dataKey ?: throw IllegalStateException("Vault is locked") }

    // ---- setup / unlock -------------------------------------------------------------------

    suspend fun setup(pin: CharArray) = withContext(Dispatchers.Default) { setupBlocking(pin) }

    suspend fun unlock(pin: CharArray): UnlockResult = withContext(Dispatchers.Default) { unlockBlocking(pin) }

    /** True if [pin] is the current vault PIN (used to keep the emergency PIN different). */
    suspend fun isCurrentPin(pin: CharArray): Boolean = withContext(Dispatchers.Default) {
        unwrapWithPin(readState(), pin)?.also { it.fill(0) } != null
    }

    @Synchronized
    fun lock() {
        dataKey?.fill(0)
        dataKey = null
        files.clearCache()
        if (_status.value == Status.UNLOCKED) _status.value = Status.LOCKED
    }

    /** Irreversibly destroys all vault data and keys. */
    @Synchronized
    fun wipe() {
        destroy()
        _status.value = Status.NEW
    }

    private fun destroy() {
        lock()
        root.deleteRecursively()
        KeystoreKeys.deleteAll()
    }

    @Synchronized
    private fun setupBlocking(pin: CharArray) {
        val key = Gcm.randomBytes(32)
        val salt = Gcm.randomBytes(16)
        root.mkdirs()
        writeState(VaultState(PinKdf.ITERATIONS, salt, wrapWithPin(key, pin, salt, PinKdf.ITERATIONS)))
        open(key)
    }

    @Synchronized
    private fun unlockBlocking(pin: CharArray): UnlockResult {
        val state = readState()
        val now = System.currentTimeMillis()
        if (state.lockoutUntil > now) return UnlockResult.LockedOut(state.lockoutUntil - now)

        val key = unwrapWithPin(state, pin)
        val isPanic = state.panicHash != null && matchesPanic(state, pin)
        return when {
            key != null -> {
                writeState(state.copy(failedAttempts = 0, lockoutUntil = 0))
                open(key)
                UnlockResult.Success
            }
            isPanic -> {
                // Duress: silently destroy everything and present a fresh, empty vault
                // that opens with the same PIN, indistinguishable from a normal unlock.
                destroy()
                setupBlocking(pin)
                UnlockResult.Success
            }
            else -> {
                val failed = state.failedAttempts + 1
                val delay = lockoutDelayMs(failed)
                writeState(state.copy(failedAttempts = failed, lockoutUntil = if (delay > 0) now + delay else 0))
                if (delay > 0) UnlockResult.LockedOut(delay) else UnlockResult.Wrong
            }
        }
    }

    private fun open(key: ByteArray) {
        dataKey = key
        _status.value = Status.UNLOCKED
    }

    private fun wrapWithPin(key: ByteArray, pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val pinKey = PinKdf.derive(pin, salt, iterations)
        try {
            val inner = Gcm.encrypt(SecretKeySpec(pinKey, "AES"), key, AAD_PIN)
            return Gcm.encrypt(KeystoreKeys.deviceKey(), inner, AAD_DEVICE)
        } finally {
            pinKey.fill(0)
        }
    }

    private fun unwrapWithPin(state: VaultState, pin: CharArray): ByteArray? {
        val inner = Gcm.decrypt(KeystoreKeys.deviceKey(), state.pinWrapped, AAD_DEVICE)
        val pinKey = PinKdf.derive(pin, state.pinSalt, state.iterations)
        return try {
            Gcm.decrypt(SecretKeySpec(pinKey, "AES"), inner, AAD_PIN)
        } catch (e: AEADBadTagException) {
            null
        } finally {
            pinKey.fill(0)
        }
    }

    // ---- emergency (duress) PIN ----------------------------------------------------------

    val hasPanicPin: Boolean get() = stateFile.exists() && readState().panicHash != null

    /** Returns false if [pin] equals the vault PIN. */
    suspend fun setPanicPin(pin: CharArray): Boolean = withContext(Dispatchers.Default) {
        if (isCurrentPin(pin)) return@withContext false
        synchronized(this@Vault) {
            val state = readState()
            val salt = Gcm.randomBytes(16)
            val hash = PinKdf.derive(pin, salt, state.iterations)
            val sealed = Gcm.encrypt(KeystoreKeys.deviceKey(), hash, AAD_PANIC)
            hash.fill(0)
            writeState(state.copy(panicSalt = salt, panicHash = sealed))
        }
        true
    }

    @Synchronized
    fun clearPanicPin() {
        writeState(readState().copy(panicSalt = null, panicHash = null))
    }

    private fun matchesPanic(state: VaultState, pin: CharArray): Boolean {
        val expected = Gcm.decrypt(KeystoreKeys.deviceKey(), state.panicHash!!, AAD_PANIC)
        val actual = PinKdf.derive(pin, state.panicSalt!!, state.iterations)
        return MessageDigest.isEqual(expected, actual).also {
            expected.fill(0)
            actual.fill(0)
        }
    }

    // ---- biometrics ----------------------------------------------------------------------

    val isBiometricEnabled: Boolean get() = stateFile.exists() && readState().bioWrapped != null

    /** Cipher to pass to BiometricPrompt when turning fingerprint unlock on. */
    fun biometricEnrollCipher(): Cipher =
        Cipher.getInstance(Gcm.TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, KeystoreKeys.createBiometricKey())
        }

    @Synchronized
    fun completeBiometricEnroll(authenticated: Cipher) {
        val key = dataKey ?: throw IllegalStateException("Vault is locked")
        val wrapped = authenticated.iv + authenticated.doFinal(key)
        writeState(readState().copy(bioWrapped = wrapped))
    }

    /**
     * Cipher to pass to BiometricPrompt on the lock screen, or null if biometric
     * unlock is off or was invalidated (e.g. a new fingerprint was enrolled).
     */
    fun biometricUnlockCipher(): Cipher? {
        val blob = readState().bioWrapped ?: return null
        val key = KeystoreKeys.biometricKey() ?: run { disableBiometric(); return null }
        val params: AlgorithmParameterSpec = GCMParameterSpec(Gcm.TAG_BITS, blob, 0, Gcm.IV_SIZE)
        return try {
            Cipher.getInstance(Gcm.TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, params) }
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException and friends: fall back to the PIN.
            disableBiometric()
            null
        }
    }

    @Synchronized
    fun completeBiometricUnlock(authenticated: Cipher): Boolean {
        val state = readState()
        val blob = state.bioWrapped ?: return false
        val key = runCatching {
            authenticated.doFinal(blob, Gcm.IV_SIZE, blob.size - Gcm.IV_SIZE)
        }.getOrNull() ?: return false
        writeState(state.copy(failedAttempts = 0, lockoutUntil = 0))
        open(key)
        return true
    }

    @Synchronized
    fun disableBiometric() {
        KeystoreKeys.deleteBiometricKey()
        if (stateFile.exists()) writeState(readState().copy(bioWrapped = null))
    }

    // ---- persistence ---------------------------------------------------------------------

    private fun readState(): VaultState = VaultState.fromJson(String(stateFile.readFully()))

    private fun writeState(state: VaultState) {
        val out = stateFile.startWrite()
        try {
            out.write(state.toJson().toByteArray())
            stateFile.finishWrite(out)
        } catch (e: Exception) {
            stateFile.failWrite(out)
            throw e
        }
    }

    private companion object {
        val AAD_PIN = "spiros.pin".toByteArray()
        val AAD_DEVICE = "spiros.device".toByteArray()
        val AAD_PANIC = "spiros.panic".toByteArray()

        /** 5 free attempts, then 30s, 1m, 2m, ... capped at 1h. */
        fun lockoutDelayMs(failed: Int): Long {
            if (failed < 5) return 0
            return (30_000L shl (failed - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)
        }
    }
}
