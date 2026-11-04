package com.spiros.app.remote

import android.content.Context
import com.spiros.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The SpirOS Linux app store: a curated catalog (assets/linux/store.json) of Ubuntu
 * apps that work on SpirOS Linux, plus search over the whole Ubuntu archive. The
 * engine installs them with apt (signed official archive) in the background.
 */
object LinuxStore {
    data class Item(
        val id: String,
        val packages: String,
        val exec: String,
        val category: String,
        val sizeMb: Int,
        val name: String,
        val description: String,
    ) {
        /** The package that tells whether the app is installed. */
        val main get() = packages.substringBefore(' ')
    }

    val CATEGORIES = listOf("trading", "dev", "office", "media", "graphics", "internet", "tools")

    fun categoryLabel(category: String) = when (category) {
        "trading" -> R.string.store_cat_trading
        "dev" -> R.string.store_cat_dev
        "office" -> R.string.store_cat_office
        "media" -> R.string.store_cat_media
        "graphics" -> R.string.store_cat_graphics
        "internet" -> R.string.store_cat_internet
        else -> R.string.store_cat_tools
    }

    fun categoryIcon(category: String) = when (category) {
        "dev" -> R.drawable.ic_store_dev
        "office" -> R.drawable.ic_store_office
        "media" -> R.drawable.ic_store_media
        "graphics" -> R.drawable.ic_store_graphics
        "internet" -> R.drawable.ic_store_internet
        else -> R.drawable.ic_store_tools
    }

    @Volatile private var cache: List<Item>? = null

    fun catalog(context: Context): List<Item> = cache ?: run {
        val ar = Locale.getDefault().language == "ar"
        val json = context.assets.open("linux/store.json").bufferedReader().use { JSONObject(it.readText()) }
        val a = json.getJSONArray("apps")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(
                id = it.getString("id"),
                packages = it.getString("packages"),
                exec = it.optString("exec"),
                category = it.optString("category", "tools"),
                sizeMb = it.optInt("size"),
                name = if (ar) it.optString("name_ar", it.getString("name")) else it.getString("name"),
                description = if (ar) it.optString("desc_ar", it.optString("desc")) else it.optString("desc"),
            )
        }.also { cache = it }
    }

    /** Installed packages (name to version) among the catalog, as last read from the engine. */
    private val _installed = MutableStateFlow<Map<String, String>>(emptyMap())
    val installed: StateFlow<Map<String, String>> = _installed.asStateFlow()

    private const val PREFS = "linux_store"

    /** Last known installed catalog apps, so Home can show them before asking the engine. */
    fun load(context: Context) {
        if (_installed.value.isNotEmpty()) return
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("installed", null) ?: return
        runCatching { _installed.value = parse(saved) }
    }

    private fun parse(json: String): Map<String, String> {
        val o = JSONObject(json)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** Asks the engine which catalog apps (and [extra] packages) are installed. */
    suspend fun refresh(context: Context, extra: Collection<String> = emptyList()) {
        val names = (catalog(context).map { it.main } + extra).distinct().joinToString(" ")
        val json = Engine.call(context) { it.linuxInstalled(names) } ?: return
        _installed.value = runCatching { parse(json) }.getOrDefault(emptyMap())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("installed", json).apply()
    }

    data class Found(val name: String, val description: String)

    suspend fun search(context: Context, query: String): List<Found> {
        val json = Engine.call(context) { it.linuxSearch(query) } ?: return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getJSONObject(it) }.map { Found(it.getString("name"), it.optString("desc")) }
        }.getOrDefault(emptyList())
    }

    suspend fun upgradable(context: Context): List<String> =
        Engine.parseStrings(Engine.call(context) { it.linuxUpgradable() })

    /** A published Windows program package: checksum and download size. */
    data class Published(val sha: String, val size: Long)

    private val _published = MutableStateFlow<Map<String, Published>>(emptyMap())
    /** base / mt5 / mt4 as published (checked at most every 30 minutes). */
    val published: StateFlow<Map<String, Published>> = _published.asStateFlow()
    @Volatile private var publishedAt = 0L

    suspend fun refreshPublished(force: Boolean = false) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - publishedAt < 30 * 60_000 && _published.value.isNotEmpty()) return@withContext
        val base = "https://github.com/inzohussein-blip/SpirOS/releases/download/linux-rootfs/"
        val found = mapOf("base" to "wine-base-arm64", "mt5" to "mt5-arm64", "mt4" to "mt4-arm64").mapNotNull { (id, file) ->
            runCatching {
                val sha = ApkInstaller.text("$base$file.tar.xz.sha256").trim().substringBefore(' ').lowercase()
                val size = runCatching { com.spiros.app.linux.Downloader.size("$base$file.tar.xz") }.getOrDefault(0L).coerceAtLeast(0L)
                id to Published(sha, size)
            }.getOrNull()
        }.toMap()
        if (found.isNotEmpty()) { _published.value = found; publishedAt = System.currentTimeMillis() }
    }

    /** Installed Windows programs with a newer package (their own or the shared base). */
    fun addonUpdates(status: Engine.Status, published: Map<String, Published>): Set<String> =
        LinuxApps.ADDONS.filter { id ->
            status.addonInstalled(id) && published[id] != null &&
                (published[id]!!.sha != status.addons[id] || (published["base"] != null && published["base"]!!.sha != status.addons["base"]))
        }.toSet()

    /** Bytes to download for [id]: the parts not installed or out of date. */
    fun addonDownloadSize(status: Engine.Status, published: Map<String, Published>, id: String): Long {
        var size = 0L
        published["base"]?.let { if (it.sha != status.addons["base"]) size += it.size }
        published[id]?.let { if (it.sha != status.addons[id]) size += it.size }
        return size
    }

    /** "Download X MB?" asked before a big download (shown by DownloadConfirmHost). */
    data class Confirm(val title: String, val bytes: Long, val start: () -> Unit)

    private val _confirm = MutableStateFlow<Confirm?>(null)
    val confirm: StateFlow<Confirm?> = _confirm.asStateFlow()
    fun ask(c: Confirm?) { _confirm.value = c }

    fun install(context: Context, packages: String) = Engine.send(context) { it.linuxApt("install", packages) }
    fun remove(context: Context, packages: String) = Engine.send(context) { it.linuxApt("remove", packages) }
    fun upgradeAll(context: Context) = Engine.send(context) { it.linuxApt("upgrade", "") }

    /** Installed catalog apps that have a window, as Linux apps SpirOS can open. */
    fun apps(context: Context, installed: Map<String, String>): List<LinuxApps.App> =
        catalog(context).filter { it.exec.isNotBlank() && it.main in installed }.map {
            LinuxApps.App("store:${it.id}", 0, it.exec, categoryIcon(it.category), title = it.name, packages = it.packages)
        }
}
