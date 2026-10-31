package com.spiros.app.shield

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Domains blocked by the Shield (DNS) and the private browser. A small built-in list
 * always works offline; the optional full list is StevenBlack's unified hosts file.
 * A rule also blocks every subdomain ("doubleclick.net" blocks "ad.doubleclick.net").
 */
object Blocklist {
    private const val FULL_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private val SPACES = Regex("\\s+")

    @Volatile
    private var domains: Set<String> = emptySet()

    @Volatile
    private var loaded = false

    val size: Int get() = domains.size

    fun fullFile(context: Context) = File(context.noBackupFilesDir, "shield/hosts.txt")

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val set = HashSet<String>(4096)
            context.assets.open("shield/base.txt").bufferedReader().useLines { lines ->
                lines.forEach { parse(it)?.let(set::add) }
            }
            if (ShieldSettings.useFullList(context)) {
                fullFile(context).takeIf { it.isFile }?.bufferedReader()?.useLines { lines ->
                    lines.forEach { parse(it)?.let(set::add) }
                }
            }
            domains = set
            loaded = true
        }
    }

    fun reload(context: Context) {
        loaded = false
        ensureLoaded(context)
    }

    fun isBlocked(host: String): Boolean {
        val set = domains
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun fullListIsStale(context: Context): Boolean {
        val file = fullFile(context)
        return !file.isFile || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
    }

    /** Downloads the full list (blocking). Returns true on success. */
    fun downloadFull(context: Context): Boolean = runCatching {
        val target = fullFile(context).apply { parentFile?.mkdirs() }
        val tmp = File(target.path + ".part")
        val connection = URL(FULL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "rename failed" }
        } finally {
            connection.disconnect()
            tmp.delete()
        }
        reload(context)
    }.isSuccess

    private fun parse(line: String): String? {
        val text = line.substringBefore('#').trim()
        if (text.isEmpty()) return null
        val parts = text.split(SPACES)
        val domain = when {
            parts.size == 1 -> parts[0]
            parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" -> parts[1]
            else -> return null
        }.lowercase()
        if ('.' !in domain || domain == "0.0.0.0" || domain.endsWith(".localdomain")) return null
        return domain
    }
}
