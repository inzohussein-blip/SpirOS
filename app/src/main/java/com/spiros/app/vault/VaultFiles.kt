package com.spiros.app.vault

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.LruCache
import android.util.Size
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.spiros.app.vault.crypto.Gcm
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val added: Long,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/**
 * Encrypted file store. File contents use Tink's streaming AES-256-GCM-HKDF
 * (1 MiB segments, so large videos never need to fit in memory); the index and
 * thumbnails use AES-GCM with keys derived from the vault data key.
 * File names, types and sizes are inside the encrypted index, never on disk in clear.
 */
class VaultFiles internal constructor(
    private val dir: File,
    private val dataKey: () -> ByteArray,
) {
    private val lock = Any()
    private val thumbCache = LruCache<String, Bitmap>(64)

    private val blobDir get() = File(dir, "blobs").apply { mkdirs() }
    private val thumbDir get() = File(dir, "thumbs").apply { mkdirs() }
    private val indexFile get() = AtomicFile(File(dir, "index.bin"))

    fun list(): List<VaultItem> = synchronized(lock) { readIndex() }.sortedByDescending { it.added }

    /** Encrypts [uri] into the vault. Returns true if the original was also deleted. */
    fun import(resolver: ContentResolver, uri: Uri, deleteOriginal: Boolean): Boolean {
        val id = UUID.randomUUID().toString()
        val (name, size) = queryNameAndSize(resolver, uri)
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val blob = File(blobDir, id)
        val tmp = File(blobDir, "$id.tmp")
        try {
            val input = resolver.openInputStream(uri) ?: error("Cannot open $uri")
            input.use { src ->
                streaming().newEncryptingStream(FileOutputStream(tmp), id.toByteArray()).use { dst ->
                    src.copyTo(dst, BUFFER)
                }
            }
            check(tmp.renameTo(blob)) { "rename failed" }
            makeThumbnail(resolver, uri, mime)?.let { jpeg ->
                File(thumbDir, id).writeBytes(Gcm.encrypt(subKey(THUMB_LABEL), jpeg, id.toByteArray()))
            }
            synchronized(lock) {
                writeIndex(readIndex() + VaultItem(id, name, mime, size, System.currentTimeMillis()))
            }
        } catch (e: Exception) {
            tmp.delete()
            blob.delete()
            File(thumbDir, id).delete()
            throw e
        }
        return deleteOriginal && runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    fun thumbnail(id: String): Bitmap? {
        thumbCache.get(id)?.let { return it }
        val file = File(thumbDir, id)
        if (!file.exists()) return null
        val jpeg = Gcm.decrypt(subKey(THUMB_LABEL), file.readBytes(), id.toByteArray())
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.also { thumbCache.put(id, it) }
    }

    /** Decrypts and decodes an image, downscaled so its longest side is at most [maxSide]. */
    fun loadImage(id: String, maxSide: Int): Bitmap {
        val bytes = openDecrypted(id).use { it.readBytes() }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) decoder.setTargetSampleSize(longest / maxSide)
        }
    }

    fun export(id: String, resolver: ContentResolver, target: Uri) {
        val output = resolver.openOutputStream(target) ?: error("Cannot write $target")
        output.use { dst -> openDecrypted(id).use { it.copyTo(dst, BUFFER) } }
    }

    fun delete(id: String) {
        synchronized(lock) { writeIndex(readIndex().filterNot { it.id == id }) }
        File(blobDir, id).delete()
        File(thumbDir, id).delete()
        thumbCache.remove(id)
    }

    internal fun clearCache() = thumbCache.evictAll()

    private fun openDecrypted(id: String): InputStream =
        streaming().newDecryptingStream(FileInputStream(File(blobDir, id)), id.toByteArray())

    private fun streaming() = AesGcmHkdfStreaming(dataKey(), "HmacSha256", 32, SEGMENT_SIZE, 0)

    /** Independent per-purpose keys so the data key is never used directly for two schemes. */
    private fun subKey(label: String): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(dataKey(), "HmacSHA256"))
        return SecretKeySpec(mac.doFinal(label.toByteArray()), "AES")
    }

    private fun readIndex(): List<VaultItem> {
        val file = indexFile
        if (!file.baseFile.exists()) return emptyList()
        val json = Gcm.decrypt(subKey(INDEX_LABEL), file.readFully(), INDEX_LABEL.toByteArray())
        val array = JSONArray(String(json))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            VaultItem(o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("size"), o.getLong("added"))
        }
    }

    private fun writeIndex(items: List<VaultItem>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("mime", it.mime)
                    .put("size", it.size).put("added", it.added),
            )
        }
        val sealed = Gcm.encrypt(subKey(INDEX_LABEL), array.toString().toByteArray(), INDEX_LABEL.toByteArray())
        dir.mkdirs()
        val file = indexFile
        val out = file.startWrite()
        try {
            out.write(sealed)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: uri.lastPathSegment ?: "file"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "file") to -1L
    }

    private fun makeThumbnail(resolver: ContentResolver, uri: Uri, mime: String): ByteArray? {
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
        val bitmap = runCatching { resolver.loadThumbnail(uri, Size(THUMB_SIZE, THUMB_SIZE), null) }.getOrNull()
            ?: if (mime.startsWith("image/")) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val shortest = minOf(info.size.width, info.size.height)
                        decoder.setTargetSampleSize((shortest / THUMB_SIZE).coerceAtLeast(1))
                    }
                }.getOrNull()
            } else {
                null
            }
        bitmap ?: return null
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private companion object {
        const val SEGMENT_SIZE = 1 shl 20
        const val BUFFER = 64 * 1024
        const val THUMB_SIZE = 320
        const val INDEX_LABEL = "spiros.index"
        const val THUMB_LABEL = "spiros.thumb"
    }
}
