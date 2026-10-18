package com.spiros.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Track B2 test guest: Alpine Linux "virt" kernel + initramfs (~30 MB), downloaded on
 * demand into app-private storage. The initramfs fetches the rest of the system
 * (modloop) through QEMU's user-mode network and boots to a root shell.
 */
object AlpineGuest {
    private const val BRANCH = "v3.20"
    private const val BASE = "https://dl-cdn.alpinelinux.org/alpine/$BRANCH/releases/aarch64/netboot"
    private const val GUEST_MIRROR = "http://dl-cdn.alpinelinux.org/alpine/$BRANCH"

    data class Files(val kernel: File, val initrd: File)

    fun dir(context: Context) = File(context.noBackupFilesDir, "lab/alpine")

    fun isDownloaded(context: Context): Boolean =
        File(dir(context), "vmlinuz-virt").isFile && File(dir(context), "initramfs-virt").isFile

    suspend fun ensure(context: Context, onProgress: (Float) -> Unit): Files = withContext(Dispatchers.IO) {
        val dir = dir(context).apply { mkdirs() }
        val names = listOf("vmlinuz-virt", "initramfs-virt")
        names.forEachIndexed { i, name ->
            val target = File(dir, name)
            if (!target.isFile) download("$BASE/$name", target) { p -> onProgress((i + p) / names.size) }
        }
        onProgress(1f)
        Files(File(dir, names[0]), File(dir, names[1]))
    }

    /** Kernel command line: serial console, DHCP, and where to fetch the rest of Alpine. */
    fun kernelArgs(): String = listOf(
        "console=ttyAMA0",
        "ip=dhcp",
        "alpine_repo=$GUEST_MIRROR/main",
        "modloop=$GUEST_MIRROR/releases/aarch64/netboot/modloop-virt",
    ).joinToString(" ")

    private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val tmp = File(target.path + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} for $url" }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            check(tmp.renameTo(target)) { "rename failed" }
        } finally {
            connection.disconnect()
            tmp.delete()
        }
    }
}
