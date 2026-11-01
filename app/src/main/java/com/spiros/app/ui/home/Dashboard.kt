package com.spiros.app.ui.home

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spiros.app.R
import com.spiros.app.SpirApp
import com.spiros.app.perf.AppProcesses
import com.spiros.app.shield.ShieldSettings
import com.spiros.app.shield.ShieldVpnService
import com.spiros.app.ui.Disguise
import com.spiros.app.ui.ScreenSecurity
import com.spiros.app.ui.theme.LocalElegant
import com.spiros.app.ui.theme.ToolColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private data class Stats(
    val ramUsedPct: Float,
    val ramText: String,
    val spirosMb: Long,
    val spirosPct: Float,
    val vaultMb: Long,
    val vaultPct: Float,
    val security: Int,
)

/** Live gauges: phone RAM, SpirOS's Linux/AI/Lab memory, encrypted vault size, security score. */
@Composable
fun Dashboard() {
    val context = LocalContext.current
    val stats by produceState<Stats?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { collect(context) }
            delay(3000)
        }
    }
    val s = stats ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Gauge(s.ramUsedPct, s.ramText, stringResource(R.string.gauge_ram), ToolColors.Browser)
        Gauge(s.spirosPct, "${s.spirosMb} MB", stringResource(R.string.gauge_spiros), ToolColors.Linux)
        Gauge(s.vaultPct, "${s.vaultMb} MB", stringResource(R.string.gauge_vault), ToolColors.Vault)
        Gauge(s.security / 100f, "${s.security}", stringResource(R.string.gauge_security), securityColor(s.security))
    }
}

private fun securityColor(score: Int) = when {
    score >= 75 -> ToolColors.Shield
    score >= 50 -> ToolColors.Space
    else -> ToolColors.Linux
}

@Composable
private fun Gauge(fraction: Float, value: String, label: String, color: Color) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "gauge")
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
    val elegant = LocalElegant.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(70.dp)) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(
                    brush = if (elegant) Brush.sweepGradient(listOf(color.copy(alpha = 0.5f), color, color.copy(alpha = 0.5f))) else Brush.linearGradient(listOf(color, color)),
                    startAngle = 135f,
                    sweepAngle = 270f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
    }
}

private fun collect(context: Context): Stats {
    val am = context.getSystemService(ActivityManager::class.java)
    val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
    val total = mem.totalMem.coerceAtLeast(1)
    val used = total - mem.availMem
    val spirosKb = AppProcesses.list().sumOf { it.rssKb }
    val vaultBytes = File(context.noBackupFilesDir, "vault").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    val free = context.noBackupFilesDir.usableSpace.coerceAtLeast(1)
    return Stats(
        ramUsedPct = used.toFloat() / total,
        ramText = "${(used * 100 / total)}%",
        spirosMb = spirosKb / 1024,
        spirosPct = (spirosKb * 1024f) / total,
        vaultMb = vaultBytes / (1024 * 1024),
        vaultPct = vaultBytes.toFloat() / (vaultBytes + free),
        security = securityScore(context),
    )
}

/** A simple, explainable score of the protections currently switched on. */
fun securityScore(context: Context): Int {
    val vault = (context.applicationContext as SpirApp).vault
    var score = 10 // files are always encrypted
    if (vault.isLockEnabled) score += 20
    if (vault.isBiometricEnabled) score += 5
    if (vault.hasPanicPin) score += 10
    if (!ScreenSecurity.screenshotsAllowed(context)) score += 15
    if (ShieldVpnService.running.value || ShieldSettings.spaceShield(context)) score += 20
    if (ShieldSettings.spaceGoogleOff(context)) score += 10
    if (Disguise.isEnabled(context)) score += 10
    return score.coerceAtMost(100)
}
