package com.spiros.app.ai

import android.app.ActivityManager
import android.content.Context
import com.spiros.app.linux.LinuxDistro
import com.spiros.app.linux.BackgroundTask
import com.spiros.app.linux.Downloader
import java.io.File

/**
 * Small open models in GGUF format (4-bit), sized for phones. They are downloaded
 * from Hugging Face into the Linux system (/root/models) and never leave the phone.
 */
object AiModels {

    data class Model(
        val id: String,
        val name: String,
        val sizeMb: Int,
        /** Minimum phone RAM (GB) for a usable experience. */
        val minRamGb: Int,
        val url: String,
    ) {
        val fileName: String get() = url.substringAfterLast('/')
    }

    val catalog = listOf(
        Model(
            "qwen2.5-0.5b", "Qwen 2.5 · 0.5B", 400, 3,
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        ),
        Model(
            "qwen2.5-1.5b", "Qwen 2.5 · 1.5B", 1100, 4,
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        ),
        Model(
            "gemma2-2b", "Gemma 2 · 2B", 1700, 6,
            "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
        ),
        Model(
            "llama3.2-3b", "Llama 3.2 · 3B", 2000, 6,
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        ),
    )

    /** Total phone RAM in GB (rounded to the nearest marketing size). */
    fun phoneRamGb(context: Context): Int {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return Math.round(info.totalMem / (1024.0 * 1024 * 1024)).toInt()
    }

    fun recommended(context: Context): Model {
        val ram = phoneRamGb(context)
        return catalog.lastOrNull { it.minRamGb <= ram } ?: catalog.first()
    }

    // ---- Engine side: model files live in the engine's Linux system ----

    fun byFileName(fileName: String) = catalog.firstOrNull { it.fileName == fileName }

    fun dir(context: Context) = File(LinuxDistro.root(context), "root/models")

    private fun file(context: Context, fileName: String) = File(dir(context), File(fileName).name)

    fun downloaded(context: Context): List<String> =
        dir(context).listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".gguf") }.map { it.name }

    fun delete(context: Context, fileName: String) = file(context, fileName).delete()

    /**
     * Downloads with resume support (large files on mobile networks), as the engine's
     * background task: pause / resume / cancel, survives leaving SpirOS.
     */
    fun download(context: Context, url: String, fileName: String) {
        require(url.startsWith("https://huggingface.co/")) { "Untrusted model source" }
        val target = file(context, fileName)
        val tmp = File(target.path + ".download")
        BackgroundTask.stage("DOWNLOADING")
        Downloader.download(url, tmp)
        // GGUF files start with the magic "GGUF"; anything else is an error page.
        val magic = tmp.inputStream().use { ByteArray(4).also { b -> it.read(b) } }
        if (String(magic) != "GGUF") {
            tmp.delete()
            error("The downloaded file is not a GGUF model")
        }
        check(tmp.renameTo(target)) { "rename failed" }
    }
}
