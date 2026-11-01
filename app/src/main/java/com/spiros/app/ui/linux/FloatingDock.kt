package com.spiros.app.ui.linux

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.spiros.app.ui.common.Haptics
import kotlin.math.roundToInt

/** A tool in the desktop's floating dock. */
data class DockTool(val icon: ImageVector, val label: String, val tint: Color, val onClick: () -> Unit)

/**
 * A single translucent button over the Linux desktop that opens the tools (keyboard,
 * touchpad, picture-in-picture, Panic, stop). Drag it anywhere so it never covers what
 * you are working on; the desktop keeps the whole screen.
 */
@Composable
fun FloatingDock(tools: List<DockTool>) {
    val view = LocalView.current
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    // Position as a fraction of the free area, so rotation keeps it on screen.
    var fx by remember { mutableFloatStateOf(1f) }
    var fy by remember { mutableFloatStateOf(0.12f) }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val button = with(density) { 52.dp.toPx() }
        val freeW = (constraints.maxWidth - button).coerceAtLeast(1f)
        val freeH = (constraints.maxHeight - button).coerceAtLeast(1f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset { IntOffset((fx * freeW).roundToInt(), (fy * freeH).roundToInt()) }
                .padding(4.dp),
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC1B2230))
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = {
                            // Snap to the nearest side, like a chat bubble.
                            fx = if (fx < 0.5f) 0f else 1f
                        }) { change, drag ->
                            change.consume()
                            fx = (fx + drag.x / freeW).coerceIn(0f, 1f)
                            fy = (fy + drag.y / freeH).coerceIn(0f, 1f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = {
                    Haptics.tick(view)
                    open = !open
                }) {
                    Icon(if (open) Icons.Rounded.Close else Icons.Rounded.Tune, contentDescription = null, tint = Color.White)
                }
            }
            AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xE61B2230))
                        .padding(vertical = 4.dp),
                ) {
                    tools.forEach { tool ->
                        IconButton(onClick = {
                            open = false
                            tool.onClick()
                        }) {
                            Icon(tool.icon, contentDescription = tool.label, tint = tool.tint)
                        }
                    }
                }
            }
        }
    }
}
