package com.spiros.app.backup

import android.content.Context
import android.content.Intent
import android.os.Build
import com.spiros.app.BuildConfig
import com.spiros.app.remote.AppUpdater
import com.spiros.app.remote.Engine
import com.spiros.app.space.SpaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A problem report the user can send to whoever helps them: versions, device, the
 * engine's status and logs (Linux, AI, MT5/MT4, apt) and the isolated space's
 * diagnostics. It holds no vault content, no keys and no personal files.
 */
object ProblemReport {
    suspend fun build(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("SpirOS problem report " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            appendLine("SpirOS build ${AppUpdater.current} (${BuildConfig.VERSION_NAME})")
            appendLine("Engine installed: ${Engine.isInstalled(context)} version ${AppUpdater.engineVersion(context)}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("== Isolated space")
            runCatching { SpaceManager(context).diagnostics().forEach { (k, v) -> appendLine("$k: $v") } }
                .onFailure { appendLine("(unavailable: ${it.message})") }
            appendLine()
            val engine = Engine.call(context) { it.diagnostics() }
            appendLine(engine ?: "== Engine: not reachable")
        }.take(400_000)
    }

    fun share(context: Context, report: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "SpirOS problem report")
            .putExtra(Intent.EXTRA_TEXT, report)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
