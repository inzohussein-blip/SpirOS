package com.spiros.app.ui.common

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spiros.app.R
import kotlin.random.Random

private const val GLYPHS = "01アイウエオカキクケコサシスセソタチツテトナニヌネノ#\$%&*+<>{}[]/\\=ABCDEF"

/**
 * Boot screen for Linux and the Lab: digital rain behind the SpirOS mark, and the
 * system's real boot messages streaming underneath, so the wait shows real progress.
 */
@Composable
fun BootOverlay(log: List<String>, title: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.secondary
    val transition = rememberInfiniteTransition(label = "boot")
    val time by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "rain")
    val pulse by transition.animateFloat(0.85f, 1.05f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val columns = remember { List(64) { RainColumn(Random.nextFloat(), 0.6f + Random.nextFloat() * 0.9f, List(18) { GLYPHS.random() }) } }
    val paint = remember {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = 16.dp.toPx()
            paint.textSize = cell * 0.85f
            val count = (size.width / cell).toInt().coerceAtMost(columns.size)
            drawIntoCanvas { canvas ->
                for (c in 0 until count) {
                    val col = columns[c]
                    val head = ((time * col.speed + col.offset) % 1f) * (size.height + cell * col.glyphs.size)
                    col.glyphs.forEachIndexed { i, ch ->
                        val y = head - i * cell
                        if (y < 0 || y > size.height) return@forEachIndexed
                        val fade = 1f - i / col.glyphs.size.toFloat()
                        paint.color = (if (i == 0) Color.White else accent).copy(alpha = 0.55f * fade).toArgb()
                        canvas.nativeCanvas.drawText(ch.toString(), c * cell, y, paint)
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(120.dp).scale(pulse).alpha(0.6f + (pulse - 0.85f) * 2f),
            )
            Spacer(Modifier.height(8.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            // The real boot messages, newest at the bottom.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(8.dp),
            ) {
                log.takeLast(10).forEach { line ->
                    Text(
                        line,
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private class RainColumn(val offset: Float, val speed: Float, val glyphs: List<Char>)
