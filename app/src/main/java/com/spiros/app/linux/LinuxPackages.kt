package com.spiros.app.linux

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * apt inside the Linux system, for the SpirOS app store: what is installed, search,
 * updates, and install / remove / upgrade as the engine's [BackgroundTask] (kind "apt").
 * Only the signed Ubuntu archive configured in the image is used.
 */
object LinuxPackages {
    private val NAME = Regex("^[a-z0-9][a-z0-9+.-]{0,80}$")
    private const val APT = "apt-get -y -q -o APT::Sandbox::User=root -o Dpkg::Use-Pty=0 " +
        "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"

    /** Valid package names only (they end up on a shell command line). */
    fun names(packages: String): List<String> =
        packages.split(' ', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.onEach {
            require(NAME.matches(it)) { "Invalid package name: $it" }
        }

    private fun shell(context: Context, script: String, timeoutSec: Long = 120): Pair<Int, List<String>> {
        val root = LinuxDistro.root(context)
        check(LinuxDistro.isInstalled(context)) { "Linux is not installed" }
        val p = LinuxSession.command(context, root, listOf("DEBIAN_FRONTEND=noninteractive", "/bin/sh", "-c", script)).start()
        val lines = p.inputStream.bufferedReader().readLines()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly()
        return runCatching { p.exitValue() }.getOrDefault(-1) to lines
    }

    /** Refresh the package lists when they are missing or older than a day. */
    private const val REFRESH =
        "find /var/lib/apt/lists -maxdepth 1 -name '*Packages*' -mtime -1 | grep -q . || $APT update >/dev/null 2>&1; "

    /** JSON {package: installed version} for those of [packages] that are installed. */
    fun installed(context: Context, packages: String): String {
        val list = names(packages)
        if (list.isEmpty()) return "{}"
        val (_, lines) = shell(context, "dpkg-query -W -f='\${Package} \${db:Status-Abbrev} \${Version}\\n' ${list.joinToString(" ")} 2>/dev/null")
        val out = JSONObject()
        lines.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3 && parts[1].startsWith("ii")) out.put(parts[0], parts[2])
        }
        return out.toString()
    }

    /** JSON [{name, desc}] of up to 60 packages whose name matches [query]. */
    fun search(context: Context, query: String): String {
        val q = query.trim().lowercase().filter { it.isLetterOrDigit() || it in "+.-" }.take(40)
        if (q.length < 2) return "[]"
        val (_, lines) = shell(context, REFRESH + "apt-cache search --names-only -- '$q' | head -n 60")
        val out = JSONArray()
        lines.mapNotNull { l -> l.split(" - ", limit = 2).takeIf { it.size == 2 } }
            .forEach { (n, d) -> if (NAME.matches(n.trim())) out.put(JSONObject().put("name", n.trim()).put("desc", d.trim())) }
        return out.toString()
    }

    /** JSON [names] of installed packages with an update (refreshes the lists first). */
    fun upgradable(context: Context): String {
        val (_, lines) = shell(context, "$APT update >/dev/null 2>&1; apt list --upgradable 2>/dev/null", timeoutSec = 300)
        val out = JSONArray()
        lines.filter { '/' in it }.forEach { out.put(it.substringBefore('/')) }
        return out.toString()
    }

    /**
     * [action] = install | remove | upgrade ([packages] ignored for upgrade). Blocking;
     * call within a [BackgroundTask]. Progress comes from apt's status lines.
     */
    fun apply(context: Context, action: String, packages: String) {
        val list = names(packages)
        val command = when (action) {
            "install" -> { require(list.isNotEmpty()); "$APT --no-install-recommends install ${list.joinToString(" ")}" }
            "remove" -> { require(list.isNotEmpty()); "$APT remove ${list.joinToString(" ")} && $APT autoremove" }
            "upgrade" -> "$APT update && $APT upgrade"
            else -> error("Unknown action $action")
        }
        val script = "dpkg --configure -a >/dev/null 2>&1; " +
            (if (action == "install") REFRESH else "") +
            command.replace("apt-get -y", "apt-get -o APT::Status-Fd=1 -y")
        val root = LinuxDistro.root(context)
        check(LinuxDistro.isInstalled(context)) { "Linux is not installed" }
        BackgroundTask.stage("DOWNLOADING", 1000)
        val p = LinuxSession.command(context, root, listOf("DEBIAN_FRONTEND=noninteractive", "/bin/sh", "-c", script)).start()
        var lastError: String? = null
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                BackgroundTask.checkpoint()
                val f = line.split(':')
                when {
                    f.size >= 3 && f[0] == "dlstatus" -> {
                        if (BackgroundTask.state?.stage != "DOWNLOADING") BackgroundTask.stage("DOWNLOADING", 1000)
                        f[2].toFloatOrNull()?.let { BackgroundTask.progress((it * 10).toLong(), 1000) }
                    }
                    f.size >= 3 && f[0] == "pmstatus" -> {
                        if (BackgroundTask.state?.stage != "INSTALLING") BackgroundTask.stage("INSTALLING", 1000)
                        f[2].toFloatOrNull()?.let { BackgroundTask.progress((it * 10).toLong(), 1000) }
                    }
                    line.startsWith("E:") -> lastError = line.removePrefix("E:").trim()
                }
            }
        } catch (e: BackgroundTask.Cancelled) {
            p.destroyForcibly()
            throw e
        }
        val code = p.waitFor()
        check(code == 0) { lastError ?: "apt failed ($code)" }
    }
}
