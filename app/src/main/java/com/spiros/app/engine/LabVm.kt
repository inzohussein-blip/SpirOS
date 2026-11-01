package com.spiros.app.engine

import android.content.Context
import com.spiros.app.shield.ShieldSettings
import java.io.File

/**
 * The Lab's virtual machine definition, in one place. tools/lab_boot.sh mirrors these
 * arguments for the automated boot test in CI; keep the two in sync.
 */
object LabVm {

    /** QEMU data (keyboard layouts) copied out of the APK assets, passed with -L. */
    fun dataDir(context: Context): File {
        val dir = File(context.noBackupFilesDir, "qemu-data")
        val stamp = File(dir, ".installed")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == version) return dir
        dir.deleteRecursively()
        copyAssets(context, "qemu", dir)
        stamp.writeText(version)
        return dir
    }

    fun vncSocket(context: Context) = File(context.cacheDir, "lab.vnc")

    /**
     * Upstream DNS for the guest's user-mode network. The bundled libslirp was patched
     * (tools/fetch_qemu.py) to read /data/data/com.spiros.app/files/resolv.conf, which
     * is filesDir for the phone's main user. Uses the Shield's resolver choice.
     */
    fun writeResolvConf(context: Context) {
        val primary = ShieldSettings.upstream(context).address
        val servers = (listOf(primary) + ShieldSettings.Upstream.entries.map { it.address }).distinct()
        File(context.filesDir, "resolv.conf").writeText(servers.joinToString("") { "nameserver $it\n" })
    }

    fun args(
        context: Context,
        guest: AlpineGuest.Files,
        cores: Int,
        ramMb: Int,
        graphics: Boolean,
    ): List<String> {
        writeResolvConf(context)
        val display = if (graphics) {
            listOf(
                "-device", "virtio-gpu-device",
                "-device", "virtio-keyboard-device",
                "-device", "virtio-tablet-device",
                "-vnc", "unix:" + vncSocket(context).apply { delete() }.path,
            )
        } else {
            emptyList()
        }
        return listOf(
            "-L", dataDir(context).path,
            "-M", "virt,gic-version=3",
            "-cpu", "max",
            "-smp", "$cores",
            "-m", "$ramMb",
            "-accel", "tcg,thread=multi",
            "-display", "none",
            "-nodefaults",
            "-serial", "stdio",
            "-monitor", "none",
            "-kernel", guest.kernel.path,
            "-initrd", guest.initrd.path,
            "-append", (if (graphics) "console=tty0 " else "") + AlpineGuest.kernelArgs(),
            "-netdev", "user,id=net0",
            "-device", "virtio-net-device,netdev=net0",
            "-device", "virtio-rng-device",
        ) + display
    }

    private fun copyAssets(context: Context, path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            // A file (or a missing directory, in builds without the engine).
            runCatching {
                context.assets.open(path).use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            return
        }
        target.mkdirs()
        children.forEach { copyAssets(context, "$path/$it", File(target, it)) }
    }
}
