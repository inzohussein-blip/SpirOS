package com.spiros.app.engine

import android.content.Context
import com.spiros.app.vault.VaultFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Isolated mode: Debian 13 in its own virtual machine (QEMU), separate from Android
 * and from SpirOS Linux. The downloaded base disk is shared and read-only; everything
 * the system writes goes to this phone's own overlay disk, encrypted with LUKS. Its
 * key is a random secret kept in the SpirOS vault, so the disk opens only while the
 * vault is unlocked, and wiping the vault makes it unreadable.
 */
object IsolatedGuest {
    private const val BASE = "https://github.com/inzohussein-blip/SpirOS/releases/download/isolated-debian"
    private val FILES = listOf("debian-arm64.qcow2", "vmlinuz-arm64", "initrd-arm64.img")
    private const val KEY_LABEL = "isolated-disk-key"
    const val READY = "SPIROS_ISOLATED_READY"

    fun dir(context: Context) = File(context.noBackupFilesDir, "isolated")
    private fun disk(context: Context) = File(dir(context), "disk.qcow2")
    private fun keyFile(context: Context) = File(context.cacheDir, "isolated.key")

    fun isDownloaded(context: Context) = FILES.all { File(dir(context), it).isFile }
    fun hasDisk(context: Context) = disk(context).isFile

    /** Downloads (and verifies) the base disk and the kernel, once. */
    suspend fun ensure(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dir = dir(context).apply { mkdirs() }
        FILES.forEachIndexed { i, name ->
            val target = File(dir, name)
            if (target.isFile) return@forEachIndexed
            val expected = String(fetch("$BASE/$name.sha256")).trim().substringBefore(' ').lowercase()
            val part = File(dir, "$name.part")
            download("$BASE/$name", part) { p -> onProgress((i + p) / FILES.size) }
            check(sha256(part) == expected) { "Checksum mismatch: the download is damaged or was altered" }
            check(part.renameTo(target))
        }
        onProgress(1f)
    }

    /** The disk key from the vault (created the first time). */
    private fun key(files: VaultFiles): String {
        files.readPrivate(KEY_LABEL)?.let { return String(it) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        files.writePrivate(KEY_LABEL, key.toByteArray())
        return key
    }

    /**
     * Writes the key where QEMU reads it at start (private cache, removed right after
     * the machine has opened its disk), and creates the encrypted overlay if needed.
     */
    fun prepare(context: Context, files: VaultFiles) {
        val keyFile = keyFile(context)
        keyFile.writeText(key(files))
        keyFile.setReadable(false, false); keyFile.setReadable(true, true)
        if (!hasDisk(context)) {
            val lib = context.applicationInfo.nativeLibraryDir
            val img = File(lib, "libqemu-img.so")
            check(img.isFile) { "qemu-img is not in this build" }
            val p = ProcessBuilder(
                img.path, "create", "-q", "-f", "qcow2",
                "-b", File(dir(context), FILES[0]).path, "-F", "qcow2",
                "--object", "secret,id=sec0,file=${keyFile.path}",
                "-o", "encrypt.format=luks,encrypt.key-secret=sec0",
                disk(context).path, "8G",
            ).redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = lib
                environment()["TMPDIR"] = context.cacheDir.path
            }.start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "Could not create the encrypted disk: $out" }
        }
    }

    /** Removes the key file once QEMU has read it. */
    fun forgetKey(context: Context) { keyFile(context).delete() }

    /** Deletes this phone's encrypted disk (the system starts fresh next time). */
    fun wipeDisk(context: Context, files: VaultFiles) {
        disk(context).delete()
        files.writePrivate(KEY_LABEL, null)
    }

    fun args(context: Context, cores: Int, ramMb: Int, graphics: Boolean): List<String> {
        LabVm.writeResolvConf(context)
        val dir = dir(context)
        val display = if (graphics) listOf(
            "-device", "virtio-gpu-device",
            "-device", "virtio-keyboard-device",
            "-device", "virtio-tablet-device",
            "-vnc", "unix:" + LabVm.vncSocket(context).apply { delete() }.path,
        ) else emptyList()
        return listOf(
            "-L", LabVm.dataDir(context).path,
            "-M", "virt,gic-version=3",
            "-cpu", "max",
            "-smp", "$cores",
            "-m", "$ramMb",
            "-accel", "tcg,thread=multi",
            "-display", "none",
            "-nodefaults",
            "-serial", "stdio",
            "-monitor", "none",
            "-object", "secret,id=sec0,file=${keyFile(context).path}",
            "-blockdev", "driver=qcow2,node-name=d0,file.driver=file,file.filename=${disk(context).path},encrypt.key-secret=sec0",
            "-device", "virtio-blk-device,drive=d0",
            "-kernel", File(dir, FILES[1]).path,
            "-initrd", File(dir, FILES[2]).path,
            "-append", (if (graphics) "console=tty0 " else "") + "root=/dev/vda rw console=ttyAMA0",
            "-netdev", "user,id=net0",
            "-device", "virtio-net-device,netdev=net0",
            "-device", "virtio-rng-device",
        ) + display
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "SpirOS")
    }

    private fun fetch(url: String): ByteArray {
        val c = open(url)
        try { check(c.responseCode == 200) { "HTTP ${c.responseCode}" }; return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    /** Resumes a partial download after a dropped connection. */
    private fun download(url: String, part: File, onProgress: (Float) -> Unit) {
        val from = if (part.isFile) part.length() else 0L
        val c = open(url)
        try {
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            check(code == 200 || code == 206) { "HTTP $code" }
            val append = code == 206
            val total = (if (append) from else 0L) + c.contentLengthLong
            java.io.FileOutputStream(part, append).use { out ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var done = if (append) from else 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
