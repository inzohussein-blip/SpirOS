package com.spiros.app.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Linux user accounts, managed from SpirOS instead of the terminal. Commands run in
 * the Debian system as (fake) root through proot; passwords are passed on stdin,
 * never on a command line.
 */
object LinuxAccounts {

    data class User(val name: String, val uid: Int, val gid: Int, val home: String, val admin: Boolean)

    private const val PREFS = "linux"
    private const val KEY_DESKTOP_USER = "desktop_user"
    private val NAME = Regex("^[a-z_][a-z0-9_-]{0,31}$")

    val ROOT = User("root", 0, 0, "/root", admin = true)

    fun isValidName(name: String) = NAME.matches(name) && name != "root"

    fun isValidPassword(password: String) = password.length >= 4 && password.none { it == '\n' || it == '\r' }

    /** root plus the regular accounts (uid 1000–59999), read straight from the system's files. */
    fun users(context: Context): List<User> {
        val root = LinuxDistro.root(context)
        val admins = File(root, "etc/group").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("sudo:") }
            ?.substringAfterLast(':')?.split(',')?.map { it.trim() }?.toSet()
            .orEmpty()
        val regular = File(root, "etc/passwd").takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val f = line.split(':')
            val uid = f.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            if (uid !in 1000..59999) return@mapNotNull null
            User(f[0], uid, f[3].toIntOrNull() ?: uid, f.getOrElse(5) { "/home/${f[0]}" }, f[0] in admins)
        }
        return listOf(ROOT) + regular
    }

    /** The account the desktop runs as (root by default). */
    fun desktopUser(context: Context): User {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DESKTOP_USER, "root")
        return users(context).firstOrNull { it.name == name } ?: ROOT
    }

    fun setDesktopUser(context: Context, name: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DESKTOP_USER, name).apply()

    suspend fun addUser(context: Context, name: String, password: String, admin: Boolean): Result<Unit> {
        require(isValidName(name) && isValidPassword(password))
        val groups = if (admin) "-G sudo " else ""
        return run(context, "useradd -m -s /bin/bash $groups$name && chpasswd", "$name:$password\n")
    }

    suspend fun setPassword(context: Context, name: String, password: String): Result<Unit> {
        require((name == "root" || isValidName(name)) && isValidPassword(password))
        return run(context, "chpasswd", "$name:$password\n")
    }

    suspend fun setAdmin(context: Context, name: String, admin: Boolean): Result<Unit> {
        require(isValidName(name))
        return run(context, if (admin) "usermod -aG sudo $name" else "gpasswd -d $name sudo", "")
    }

    suspend fun deleteUser(context: Context, name: String): Result<Unit> {
        require(isValidName(name))
        if (desktopUser(context).name == name) setDesktopUser(context, "root")
        return run(context, "userdel -r $name", "")
    }

    /** Runs [script] as root in the Linux system, feeding [stdin]; fails with its output. */
    private suspend fun run(context: Context, script: String, stdin: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val p = LinuxSession.command(context, LinuxDistro.root(context), listOf("/bin/sh", "-c", script)).start()
            p.outputStream.use { it.write(stdin.toByteArray()) }
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            check(code == 0) { out.trim().ifEmpty { "exit $code" } }
        }
    }
}
