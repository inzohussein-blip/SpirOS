package com.spiros.app.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spiros.app.engine.VncClient
import kotlin.math.roundToInt

private const val SCROLL_UP = -1
private const val SCROLL_DOWN = -2

/** Scroll at the centre of the guest screen (the wheel acts on the window under it). */
private fun lastX(client: VncClient) = (client.bitmap.value?.width ?: 0) / 2
private fun lastY(client: VncClient) = (client.bitmap.value?.height ?: 0) / 2

/** Maps between the on-screen canvas and guest pixels (aspect-fit, centred). */
private class Fit(var scale: Float = 1f, var left: Float = 0f, var top: Float = 0f) {
    fun toGuest(p: Offset) = ((p.x - left) / scale).roundToInt() to ((p.y - top) / scale).roundToInt()
}

/** The guest's graphical display with touch-as-mouse and a row of special keys. */
@Composable
fun VmScreen(client: VncClient, modifier: Modifier = Modifier, controls: Boolean = true, cursor: Cursor? = null) {
    val bitmap by client.bitmap.collectAsState()
    val frame by client.frame.collectAsState()
    val fit = remember { Fit() }

    Column(modifier) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator()
            } else {
                val image = remember(bmp) { bmp.asImageBitmap() }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(client) {
                            detectTapGestures(
                                onTap = { p ->
                                    val (x, y) = fit.toGuest(p)
                                    cursor?.let { it.x = x.toFloat(); it.y = y.toFloat() }
                                    client.click(x, y)
                                },
                                onLongPress = { p ->
                                    val (x, y) = fit.toGuest(p)
                                    client.rightClick(x, y)
                                },
                            )
                        }
                        .pointerInput(client) {
                            var last = Offset.Zero
                            detectDragGestures(
                                onDragStart = { p ->
                                    last = p
                                    val (x, y) = fit.toGuest(p)
                                    client.pointer(x, y, pressed = true)
                                },
                                onDrag = { change, _ ->
                                    last = change.position
                                    val (x, y) = fit.toGuest(last)
                                    client.pointer(x, y, pressed = true)
                                },
                                onDragEnd = {
                                    val (x, y) = fit.toGuest(last)
                                    client.pointer(x, y, pressed = false)
                                },
                            )
                        },
                ) {
                    frame // redraw on every guest update
                    fit.scale = minOf(size.width / bmp.width, size.height / bmp.height)
                    val w = bmp.width * fit.scale
                    val h = bmp.height * fit.scale
                    fit.left = (size.width - w) / 2
                    fit.top = (size.height - h) / 2
                    drawImage(
                        image,
                        dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                    // Touchpad pointer: an arrow at the guest position.
                    if (cursor != null && cursor.x >= 0) {
                        val cx = fit.left + cursor.x * fit.scale
                        val cy = fit.top + cursor.y * fit.scale
                        val s = 22f
                        val arrow = Path().apply {
                            moveTo(cx, cy)
                            lineTo(cx, cy + s)
                            lineTo(cx + s * 0.28f, cy + s * 0.75f)
                            lineTo(cx + s * 0.5f, cy + s * 1.15f)
                            lineTo(cx + s * 0.65f, cy + s * 1.08f)
                            lineTo(cx + s * 0.45f, cy + s * 0.68f)
                            lineTo(cx + s * 0.8f, cy + s * 0.68f)
                            close()
                        }
                        drawPath(arrow, Color.White)
                        drawPath(arrow, Color.Black, style = Stroke(width = 2f))
                    }
                }
            }
        }
        if (controls) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                "⇞" to SCROLL_UP,
                "⇟" to SCROLL_DOWN,
                "Esc" to VncClient.KEY_ESCAPE,
                "Tab" to VncClient.KEY_TAB,
                "⌫" to VncClient.KEY_BACKSPACE,
                "←" to VncClient.KEY_LEFT,
                "↑" to VncClient.KEY_UP,
                "↓" to VncClient.KEY_DOWN,
                "→" to VncClient.KEY_RIGHT,
                "⏎" to VncClient.KEY_ENTER,
            ).forEach { (label, key) ->
                FilledTonalButton(
                    onClick = {
                        when (key) {
                            SCROLL_UP -> client.scroll(lastX(client), lastY(client), up = true)
                            SCROLL_DOWN -> client.scroll(lastX(client), lastY(client), up = false)
                            else -> client.key(key)
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(label, fontSize = 12.sp) }
            }
        }
    }
}
