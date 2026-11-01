package com.spiros.app.linux

import android.content.Context
import android.os.Build
import android.system.Os
import android.os.ParcelFileDescriptor
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * The SpirOS Linux system: Ubuntu 24.04 in Linux Mint style with the desktop the user
 * picks (Xfce, MATE or Cinnamon), built by .github/workflows/linux-rootfs.yml, or the
 * user's own root filesystem (a .tar.xz / .tar.gz / .tar from a link or a file).
 * Downloads resume, are checked against their SHA-256 and unpacked into the engine's
 * private storage. Runs as the engine's [BackgroundTask] (pause / resume / cancel).
 */
object LinuxDistro {
    private const val BASE = "https://github.com/inzohussein-blip/SpirOS/releases/download/linux-rootfs"
    private const val MARKER = ".spiros-installed"

    /** Desktops built by SpirOS, plus [CUSTOM] for the user's own system. */
    val DESKTOPS = listOf("xfce", "mate", "cinnamon")
    const val CUSTOM = "custom"

    enum class Stage { DOWNLOADING, COPYING, VERIFYING, EXTRACTING, FINISHING }

    /** Debian/Ubuntu architecture name for this phone, or null if unsupported. */
    fun arch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "x86_64" -> "amd64"
        else -> null
    }

    fun root(context: Context) = File(context.filesDir, "linux/debian")

    fun isInstalled(context: Context) = File(root(context), MARKER).isFile

    /** Which system is installed: xfce, mate, cinnamon, custom, or debian (older SpirOS). */
    fun installed(context: Context): String? {
        val marker = File(root(context), MARKER)
        if (!marker.isFile) return null
        val first = marker.readLines().firstOrNull().orEmpty()
        return if (first in DESKTOPS || first == CUSTOM) first else "debian"
    }

    fun remove(context: Context) {
        deleteTree(File(context.filesDir, "linux"))
    }

    private fun downloads(context: Context) = File(context.filesDir, "linux-download").apply { mkdirs() }

    /**
     * Installs [desktop] (one of [DESKTOPS]), or for [CUSTOM] the archive at [url] or
     * in [file]. [sha256] is optional for custom systems. Blocking; call within a
     * [BackgroundTask]. AI models already downloaded are kept.
     */
    fun install(context: Context, desktop: String, url: String?, sha256: String?, file: ParcelFileDescriptor?) {
        val arch = arch() ?: error("Unsupported CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        val dir = downloads(context)
        val archive: File
        var expected = sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        when {
            desktop in DESKTOPS -> {
                val name = "ubuntu-$desktop-$arch.tar.xz"
                BackgroundTask.stage(Stage.DOWNLOADING.name)
                expected = Downloader.text("$BASE/$name.sha256").trim().substringBefore(' ').lowercase()
                archive = File(dir, name)
                Downloader.download("$BASE/$name", archive, stamp = "$name#$expected")
            }
            desktop == CUSTOM && file != null -> {
                archive = File(dir, "custom-rootfs")
                copy(file, archive)
            }
            desktop == CUSTOM && !url.isNullOrBlank() -> {
                BackgroundTask.stage(Stage.DOWNLOADING.name)
                archive = File(dir, "custom-rootfs")
                Downloader.download(url.trim(), archive)
            }
            else -> error("Nothing to install")
        }
        try {
            if (expected != null) {
                BackgroundTask.stage(Stage.VERIFYING.name, archive.length())
                check(sha256(archive) == expected) { "Checksum mismatch: the download is damaged or was altered" }
            }
            val root = root(context)
            // Keep downloaded AI models across a system change.
            val models = File(root, "root/models")
            val keep = File(context.filesDir, "linux-models")
            if (models.isDirectory) { deleteTree(keep); models.renameTo(keep) }
            deleteTree(root)
            root.mkdirs()
            BackgroundTask.stage(Stage.EXTRACTING.name, archive.length())
            extract(archive, root)
            BackgroundTask.stage(Stage.FINISHING.name)
            flatten(root)
            prepare(root)
            if (keep.isDirectory) { File(root, "root").mkdirs(); keep.renameTo(File(root, "root/models")) }
            File(root, MARKER).writeText("$desktop\n${expected.orEmpty()}\n")
        } catch (e: Exception) {
            if (e is BackgroundTask.Cancelled) deleteTree(root(context))
            throw e
        } finally {
            // A cancelled *download* keeps its .part file and continues next time.
            archive.delete()
        }
    }

    private fun copy(file: ParcelFileDescriptor, target: File) {
        val total = file.statSize.coerceAtLeast(0)
        BackgroundTask.stage(Stage.COPYING.name, total)
        ParcelFileDescriptor.AutoCloseInputStream(file).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    BackgroundTask.checkpoint()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (done % (1 shl 20) < buffer.size) BackgroundTask.progress(done, total)
                }
            }
        }
    }

    /** Images packed inside one top folder (e.g. "ubuntu-noble/usr/…"): move its content up. */
    private fun flatten(root: File) {
        if (File(root, "usr").exists() || File(root, "bin").exists()) return
        val only = root.listFiles()?.singleOrNull { it.isDirectory } ?: return
        if (!File(only, "usr").exists()) return
        only.listFiles()?.forEach { it.renameTo(File(root, it.name)) }
        only.delete()
    }

    /** What every system needs to work inside the engine. */
    private fun prepare(root: File) {
        File(root, "tmp").mkdirs()
        val resolv = File(root, "etc/resolv.conf")
        if (!resolv.isFile || resolv.length() == 0L || java.nio.file.Files.isSymbolicLink(resolv.toPath())) {
            resolv.delete()
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 9.9.9.9\nnameserver 1.1.1.1\n")
        }
    }

    // ---- extraction ----------------------------------------------------------------

    /** Opens .tar.xz, .tar.gz or plain .tar by looking at the first bytes. */
    private fun tarStream(input: InputStream): TarArchiveInputStream {
        val buffered = BufferedInputStream(input, 1 shl 16)
        buffered.mark(8)
        val magic = ByteArray(6).also { buffered.read(it) }
        buffered.reset()
        val decompressed: InputStream = when {
            magic[0] == 0xFD.toByte() && magic[1] == '7'.code.toByte() && magic[2] == 'z'.code.toByte() -> XZCompressorInputStream(buffered)
            magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> GzipCompressorInputStream(buffered)
            magic[0] == 0x28.toByte() && magic[1] == 0xB5.toByte() -> error("zstd archives are not supported; use .tar.xz or .tar.gz")
            else -> buffered
        }
        return TarArchiveInputStream(BufferedInputStream(decompressed, 1 shl 16))
    }

    private fun extract(archive: File, root: File) {
        val total = archive.length()
        val rootPath = root.canonicalPath + File.separator
        // Never write through a symlink that leads out of the root (a malicious archive
        // could ship "etc -> /data/..." and then "etc/x"): the real parent must be inside.
        fun inside(f: File) = runCatching { (f.parentFile ?: root).canonicalPath + File.separator }
            .getOrDefault("").startsWith(rootPath)
        val counting = CountingStream(archive.inputStream().buffered(1 shl 16))
        var entries = 0
        tarStream(counting).use { tar ->
            var lastReport = 0L
            while (true) {
                BackgroundTask.checkpoint()
                val entry = tar.nextEntry ?: break
                entries++
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                // Never write outside the root (malformed or malicious archive). Symlink
                // targets may be absolute: they are resolved inside proot, not here.
                if (name.startsWith("/") || name.split('/').any { it == ".." }) continue
                val out = File(root, name)
                if (!inside(out)) continue
                when {
                    entry.isDirectory -> {
                        out.mkdirs()
                        chmod(out, entry.mode or 0b111_000_000)
                    }
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        runCatching { Os.symlink(entry.linkName, out.path) }
                    }
                    entry.isLink -> { // hard link: copy the file it points to
                        out.parentFile?.mkdirs()
                        val from = File(root, entry.linkName.removePrefix("./").removePrefix("/"))
                        if (runCatching { from.canonicalPath.startsWith(rootPath) }.getOrDefault(false)) runCatching { from.copyTo(out, overwrite = true); chmod(out, entry.mode or 0b110_000_000) }
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                        chmod(out, entry.mode or 0b110_000_000)
                    }
                    else -> Unit // device nodes, fifos: provided by proot binds
                }
                if (counting.count - lastReport > 1 shl 20) {
                    lastReport = counting.count
                    BackgroundTask.progress(counting.count, total)
                }
            }
        }
        check(entries > 10) { "This file is not a Linux root filesystem (tar archive)" }
        BackgroundTask.progress(total, total)
    }

    /**
     * Deletes a directory tree without following symbolic links (a Linux system has
     * links to absolute paths; following them would reach files outside it).
     */
    fun deleteTree(file: File) {
        val path = file.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            // Make sure we may list and delete inside (archives can ship read-only dirs).
            runCatching { Os.chmod(file.path, 0b111_000_000 or (Os.lstat(file.path).st_mode and 0b111_111)) }
            file.listFiles()?.forEach { deleteTree(it) }
        }
        runCatching { java.nio.file.Files.delete(path) }
    }

    private fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.path, mode and 0b111_111_111) }
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            var done = 0L
            while (true) {
                BackgroundTask.checkpoint()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                done += n
                if (done % (4 shl 20) < buffer.size) BackgroundTask.progress(done, file.length())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
