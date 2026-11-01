package com.spiros.app.linux

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable HTTPS downloads for the engine: data goes to "<target>.part" and is
 * continued with an HTTP Range request after a pause, a dropped mobile connection or
 * an engine restart. Progress, pause and cancel go through [BackgroundTask].
 */
object Downloader {
    private const val RETRIES = 30

    private fun open(url: String, from: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SpirOS")
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }

    fun text(url: String): String {
        val c = open(url, 0)
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode} for $url" }
            return c.inputStream.use { String(it.readBytes()) }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads [url] to [target] (resuming a previous partial download of the same URL). */
    fun download(url: String, target: File) {
        require(url.startsWith("https://")) { "Only https:// downloads are allowed" }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val source = File(target.path + ".url")
        if (!source.isFile || source.readText() != url) {
            part.delete()
            source.writeText(url)
        }
        var attempt = 0
        while (true) {
            BackgroundTask.checkpoint()
            try {
                fetch(url, part)
                break
            } catch (e: IOException) {
                if (++attempt > RETRIES) throw e
                // Network dropped (or the server closed the connection during a pause):
                // wait a little and continue from where the file ends.
                for (i in 0 until minOf(attempt, 10) * 4) {
                    Thread.sleep(250)
                    BackgroundTask.checkpoint()
                }
            }
        }
        target.delete()
        check(part.renameTo(target)) { "rename failed" }
        source.delete()
    }

    private fun fetch(url: String, part: File) {
        val already = part.length()
        val c = open(url, already)
        try {
            val code = c.responseCode
            if (code == 416 && already > 0) return // already complete
            check(code == 200 || code == 206) { "HTTP $code for $url" }
            val append = code == 206
            var done = if (append) already else 0L
            val total = if (c.contentLengthLong > 0) done + c.contentLengthLong else 0L
            BackgroundTask.progress(done, total)
            FileOutputStream(part, append).use { output ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var lastReport = done
                    while (true) {
                        BackgroundTask.checkpoint()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            BackgroundTask.progress(done, total)
                        }
                    }
                }
            }
            BackgroundTask.progress(done, total)
            if (total > 0 && done < total) throw IOException("connection closed early")
        } finally {
            c.disconnect()
        }
    }
}
