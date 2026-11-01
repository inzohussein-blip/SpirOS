package com.spiros.app.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spiros.app.R
import com.spiros.app.engine.VncClient
import kotlin.math.abs
import kotlin.math.roundToInt

/** The mouse pointer on the Linux screen, in guest pixels (moved by the touchpad). */
class Cursor {
    var x by mutableFloatStateOf(-1f)
    var y by mutableFloatStateOf(-1f)
    /** Left button held (drag with the touchpad). */
    var dragging by mutableStateOf(false)

    fun ensure(client: VncClient) {
        val b = client.bitmap.value ?: return
        if (x < 0 || y < 0) {
            x = b.width / 2f
            y = b.height / 2f
        }
    }

    fun moveBy(client: VncClient, dx: Float, dy: Float) {
        val b = client.bitmap.value ?: return
        ensure(client)
        x = (x + dx).coerceIn(0f, b.width - 1f)
        y = (y + dy).coerceIn(0f, b.height - 1f)
        client.pointer(x.roundToInt(), y.roundToInt(), pressed = dragging)
    }
}

/**
 * A laptop-style touchpad under the Linux screen: one finger moves the pointer, a tap
 * clicks, a two-finger tap right-clicks, two fingers up/down scroll, and the buttons
 * below hold (drag) or right-click.
 */
@Composable
fun Touchpad(client: VncClient, cursor: Cursor, modifier: Modifier = Modifier) {
    val pad = Color(0xFF1B2230)
    val line = Color(0xFF3A4660)
    Column(modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(pad, RoundedCornerShape(14.dp))
                .border(1.dp, line, RoundedCornerShape(14.dp))
                .pointerInput(client) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val start = System.currentTimeMillis()
                        cursor.ensure(client)
                        var fingers = 1
                        var moved = 0f
                        var scroll = 0f
                        // Faster finger = further pointer (simple acceleration).
                        val guestWidth = client.bitmap.value?.width ?: 1280
                        val base = guestWidth / size.width.toFloat() * 1.4f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            fingers = maxOf(fingers, pressed.size)
                            if (pressed.size == 1 && fingers == 1) {
                                val d = pressed[0].positionChange()
                                val speed = abs(d.x) + abs(d.y)
                                val gain = base * (1f + (speed / 40f).coerceAtMost(1.5f))
                                moved += speed
                                if (speed > 0) cursor.moveBy(client, d.x * gain, d.y * gain)
                            } else if (pressed.size >= 2) {
                                val dy = pressed.map { it.positionChange().y }.average().toFloat()
                                moved += abs(dy)
                                scroll += dy
                                while (abs(scroll) > 40f) {
                                    client.scroll(cursor.x.roundToInt(), cursor.y.roundToInt(), up = scroll > 0)
                                    scroll -= 40f * kotlin.math.sign(scroll)
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                        val quick = System.currentTimeMillis() - start < 300
                        if (moved < 24f && quick) {
                            val x = cursor.x.roundToInt()
                            val y = cursor.y.roundToInt()
                            if (fingers >= 2) client.rightClick(x, y) else client.click(x, y)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.touchpad_hint), color = Color(0xFF6F7C99), fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Left: press and hold to drag (move with the pad while holding).
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(if (cursor.dragging) line else pad, RoundedCornerShape(10.dp))
                    .border(1.dp, line, RoundedCornerShape(10.dp))
                    .pointerInput(client) {
                        detectTapGestures(onPress = {
                            cursor.ensure(client)
                            cursor.dragging = true
                            client.pointer(cursor.x.roundToInt(), cursor.y.roundToInt(), pressed = true)
                            tryAwaitRelease()
                            cursor.dragging = false
                            client.pointer(cursor.x.roundToInt(), cursor.y.roundToInt(), pressed = false)
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.touchpad_left), color = Color.White, fontSize = 12.sp) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(pad, RoundedCornerShape(10.dp))
                    .border(1.dp, line, RoundedCornerShape(10.dp))
                    .pointerInput(client) {
                        detectTapGestures(onTap = {
                            cursor.ensure(client)
                            client.rightClick(cursor.x.roundToInt(), cursor.y.roundToInt())
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.touchpad_right), color = Color.White, fontSize = 12.sp) }
        }
    }
}
