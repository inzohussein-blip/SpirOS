package com.spiros.app.backup

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.spiros.app.remote.Engine
import com.spiros.app.vault.VaultFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * One encrypted file with what SpirOS keeps: the vault's files and private records,
 * SpirOS's settings, and the MT5 / MT4 user data (accounts, experts, settings).
 *
 * Format: "SPIROSBK1\n", salt (16 bytes), PBKDF2 iterations (int), then a ZIP
 * encrypted with Tink's streaming AES-256-GCM-HKDF under a key derived from the
 * password (PBKDF2-HMAC-SHA256). The header is authenticated as associated data.
 * Nothing in it is readable without the password.
 */
object Backup {
    private val MAGIC = "SPIROSBK1\n".toByteArray()
    private const val ITERATIONS = 310_000
    private const val SEGMENT = 1 shl 20

    data class Result(val vaultFiles: Int, val records: Int, val settings: Int, val tradingFiles: Int)

    /** Settings files that must not travel: keys, pairing, the vault's own state. */
    private fun keepSettings(name: String) =
        name.endsWith(".xml") && listOf("space", "vault", "key", "token", "biometric").none { name.contains(it, ignoreCase = true) }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded

    private fun streaming(key: ByteArray) = AesGcmHkdfStreaming(key, "HmacSha256", 32, SEGMENT, 0)

    suspend fun create(context: Context, files: VaultFiles, target: Uri, password: CharArray, onProgress: (String) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val header = MAGIC + salt + java.nio.ByteBuffer.allocate(4).putInt(ITERATIONS).array()
            val key = derive(password, salt, ITERATIONS)
            var vaultCount = 0; var records = 0; var settings = 0; var trading = 0
            context.contentResolver.openOutputStream(target, "wt")!!.use { raw ->
                raw.write(header)
                streaming(key).newEncryptingStream(raw, header).use { enc ->
                    ZipOutputStream(enc).use { zip ->
                        // Vault files: the index first, so a restore can import each file
                        // straight into the vault without spooling it in clear.
                        val items = files.list()
                        val index = JSONArray()
                        items.forEach { item ->
                            index.put(JSONObject().put("id", item.id).put("name", item.name).put("mime", item.mime).put("size", item.size).put("added", item.added))
                        }
                        zip.putNextEntry(ZipEntry("vault/index.json")); zip.write(index.toString().toByteArray()); zip.closeEntry()
                        items.forEachIndexed { i, item ->
                            onProgress("vault ${i + 1}/${items.size}")
                            zip.putNextEntry(ZipEntry("vault/${item.id}"))
                            files.openItem(item.id).use { it.copyTo(zip, 1 shl 16) }
                            zip.closeEntry()
                            vaultCount++
                        }
                        // Private records (AI chat history, ...).
                        files.privateLabels().forEach { label ->
                            files.readPrivate(label)?.let { data ->
                                zip.putNextEntry(ZipEntry("private/$label")); zip.write(data); zip.closeEntry(); records++
                            }
                        }
                        // Settings.
                        onProgress("settings")
                        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter { keepSettings(it.name) }?.forEach { f ->
                            zip.putNextEntry(ZipEntry("prefs/${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); settings++
                        }
                        // MT5 / MT4 user data from the engine (through a pipe).
                        if (Engine.status.value.addons.values.any { it.isNotEmpty() }) {
                            onProgress("trading")
                            val pipe = ParcelFileDescriptor.createPipe()
                            val started = Engine.call(context) { it.exportAddonData(pipe[1]) } != null
                            pipe[1].close()
                            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                                if (started) {
                                    zip.putNextEntry(ZipEntry("trading/data.tar"))
                                    trading = (input.copyTo(zip, 1 shl 16) > 0).let { if (it) 1 else 0 }
                                    zip.closeEntry()
                                }
                            }
                        }
                    }
                }
            }
            Result(vaultCount, records, settings, trading)
        }

    class WrongPassword : Exception("Wrong password or not a SpirOS backup")

    suspend fun restore(context: Context, files: VaultFiles, source: Uri, password: CharArray, onProgress: (String) -> Unit): Result =
        withContext(Dispatchers.IO) {
            var vaultCount = 0; var records = 0; var settings = 0; var trading = 0
            context.contentResolver.openInputStream(source)!!.use { raw ->
                val data = DataInputStream(raw)
                val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
                if (!magic.contentEquals(MAGIC)) throw WrongPassword()
                val salt = ByteArray(16).also { data.readFully(it) }
                val iterations = data.readInt()
                require(iterations in 10_000..10_000_000)
                val header = MAGIC + salt + java.nio.ByteBuffer.allocate(4).putInt(iterations).array()
                val key = derive(password, salt, iterations)
                val dec: InputStream = streaming(key).newDecryptingStream(raw, header)
                val existing = files.list().map { it.name to it.size }.toSet()
                var index: Map<String, JSONObject> = emptyMap()
                run {
                    ZipInputStream(dec).use { zip ->
                        while (true) {
                            val entry = try { zip.nextEntry } catch (e: java.io.IOException) {
                                if (vaultCount + records + settings + trading == 0 && index.isEmpty()) throw WrongPassword() else throw e
                            } ?: break
                            val name = entry.name
                            if (".." in name.split('/')) continue
                            when {
                                name == "vault/index.json" -> {
                                    val a = JSONArray(String(zip.readBytes()))
                                    index = (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("id") }
                                }
                                name.startsWith("vault/") -> {
                                    val meta = index[name.removePrefix("vault/")] ?: continue
                                    val itemName = meta.getString("name")
                                    if ((itemName to meta.optLong("size")) !in existing) {
                                        // Re-encrypted with this vault's key as it is read.
                                        files.importStream(object : java.io.FilterInputStream(zip) { override fun close() {} }, itemName, meta.optString("mime", "application/octet-stream"))
                                        vaultCount++
                                        onProgress("vault $vaultCount")
                                    }
                                }
                                name.startsWith("private/") -> {
                                    val label = name.removePrefix("private/")
                                    if (files.readPrivate(label) == null) { files.writePrivate(label, zip.readBytes()); records++ }
                                }
                                name.startsWith("prefs/") -> {
                                    val fileName = name.removePrefix("prefs/")
                                    if (keepSettings(fileName) && '/' !in fileName) {
                                        File(context.applicationInfo.dataDir, "shared_prefs/$fileName").outputStream().use { zip.copyTo(it) }
                                        settings++
                                    }
                                }
                                name == "trading/data.tar" -> {
                                    onProgress("trading")
                                    val pipe = ParcelFileDescriptor.createPipe()
                                    val writer = Thread {
                                        runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> zip.copyTo(out, 1 shl 16) } }
                                    }
                                    writer.start()
                                    trading = Engine.call(context) { it.importAddonData(pipe[0]) } ?: 0
                                    pipe[0].close()
                                    writer.join()
                                }
                            }
                        }
                    }
                }
            }
            Result(vaultCount, records, settings, trading)
        }
}
