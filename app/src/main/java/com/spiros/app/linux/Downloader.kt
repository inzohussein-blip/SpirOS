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
    /** Connections used for one large file. */
    private const val PARTS = 6
    private const val PARALLEL_MIN = 16L shl 20

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

    /**
     * Downloads [url] to [target], resuming a previous partial download of the same
     * file. [stamp] identifies the content (e.g. URL + expected checksum), so a part
     * of an older build behind the same URL is never continued. Large files are
     * fetched over several connections at once (much faster on mobile networks).
     */
    fun download(url: String, target: File, stamp: String = url) {
        require(url.startsWith("https://")) { "Only https:// downloads are allowed" }
        target.parentFile?.mkdirs()
        val source = File(target.path + ".url")
        if (!source.isFile || source.readText() != stamp) {
            target.parentFile?.listFiles()?.filter { it.name.startsWith(target.name + ".p") }?.forEach { it.delete() }
            source.writeText(stamp)
        }
        val total = runCatching { size(url) }.getOrDefault(-1L)
        if (total > PARALLEL_MIN) parallel(url, target, total) else single(url, target)
        source.delete()
    }

    /** Total size if the server supports ranges, else -1. */
    fun size(url: String): Long {
        val c = open(url, 0).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            if (c.responseCode != 206) return -1
            return c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1
        } finally {
            c.disconnect()
        }
    }

    private fun single(url: String, target: File) {
        val part = File(target.path + ".part")
        var attempt = 0
        while (true) {
            BackgroundTask.checkpoint()
            try {
                fetch(url, part)
                break
            } catch (e: IOException) {
                if (++attempt > RETRIES) throw e
                backoff(attempt)
            }
        }
        target.delete()
        check(part.renameTo(target)) { "rename failed" }
    }

    /** Waits a little before retrying, staying responsive to pause and cancel. */
    private fun backoff(attempt: Int) {
        for (i in 0 until minOf(attempt, 10) * 4) {
            Thread.sleep(250)
            BackgroundTask.checkpoint()
        }
    }

    private class Segment(val start: Long, val end: Long, val file: File) {
        val length get() = end - start + 1
        val have get() = minOf(file.length(), length)
    }

    private fun parallel(url: String, target: File, total: Long) {
        val each = (total + PARTS - 1) / PARTS
        val segments = (0 until PARTS).map { i ->
            Segment(i * each, minOf(total, (i + 1) * each) - 1, File(target.path + ".p$i"))
        }.filter { it.start <= it.end }
        val done = java.util.concurrent.atomic.AtomicLong(segments.sumOf { it.have })
        BackgroundTask.progress(done.get(), total)
        var failure: Throwable? = null
        val workers = segments.map { seg ->
            Thread {
                var attempt = 0
                try {
                    while (seg.have < seg.length) {
                        BackgroundTask.checkpoint()
                        try {
                            fetchRange(url, seg, done, total)
                        } catch (e: IOException) {
                            if (++attempt > RETRIES) throw e
                            backoff(attempt)
                        }
                    }
                } catch (e: Throwable) {
                    synchronized(segments) { if (failure == null) failure = e }
                }
            }.apply { name = "download-${seg.file.name}"; start() }
        }
        workers.forEach { it.join() }
        failure?.let { throw it }
        // Join the pieces into the final file.
        val out = File(target.path + ".part")
        out.outputStream().use { o -> segments.forEach { seg -> seg.file.inputStream().use { it.copyTo(o, 1 shl 16) } } }
        check(out.length() == total) { "Download incomplete: ${out.length()} of $total bytes" }
        segments.forEach { it.file.delete() }
        target.delete()
        check(out.renameTo(target)) { "rename failed" }
    }

    private fun fetchRange(url: String, seg: Segment, done: java.util.concurrent.atomic.AtomicLong, total: Long) {
        val from = seg.start + seg.have
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SpirOS")
            setRequestProperty("Range", "bytes=$from-${seg.end}")
        }
        try {
            if (c.responseCode != 206) throw IOException("HTTP ${c.responseCode} for a range")
            FileOutputStream(seg.file, true).use { output ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var sinceReport = 0L
                    var remaining = seg.end - from + 1
                    while (remaining > 0) {
                        BackgroundTask.checkpoint()
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n < 0) throw IOException("connection closed early")
                        output.write(buffer, 0, n)
                        remaining -= n
                        sinceReport += n
                        val now = done.addAndGet(n.toLong())
                        if (sinceReport > 256 * 1024) {
                            sinceReport = 0
                            BackgroundTask.progress(now, total)
                        }
                    }
                }
            }
            BackgroundTask.progress(done.get(), total)
        } finally {
            c.disconnect()
        }
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
