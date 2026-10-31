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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spiros.app.engine.VncClient
import kotlin.math.roundToInt

/** Maps between the on-screen canvas and guest pixels (aspect-fit, centred). */
private class Fit(var scale: Float = 1f, var left: Float = 0f, var top: Float = 0f) {
    fun toGuest(p: Offset) = ((p.x - left) / scale).roundToInt() to ((p.y - top) / scale).roundToInt()
}

/** The guest's graphical display with touch-as-mouse and a row of special keys. */
@Composable
fun VmScreen(client: VncClient, modifier: Modifier = Modifier) {
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
                            detectTapGestures(onTap = { p ->
                                val (x, y) = fit.toGuest(p)
                                client.click(x, y)
                            })
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
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
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
                    onClick = { client.key(key) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(label, fontSize = 12.sp) }
            }
        }
    }
}
