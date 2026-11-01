package com.spiros.app.ui.linux

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.spiros.app.engine.VncClient
import com.spiros.app.ui.lab.Cursor
import com.spiros.app.ui.lab.VmScreen

/**
 * Linux on a second screen: a TV or monitor over HDMI / USB-C, or a wireless display
 * (Miracast / "Cast screen", where the phone supports it). Android shows our own
 * window there (Presentation API), so the phone stays free to be the touchpad and
 * keyboard.
 */
object ExternalDisplay {
    /** Screens that can show an app's own content, other than the phone's. */
    fun find(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java)
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    /** Desktop size that fits [display] (16:9-ish, capped at 1920 wide for speed). */
    fun geometryFor(display: Display): String {
        val mode = display.mode
        var w = maxOf(mode.physicalWidth, mode.physicalHeight)
        var h = minOf(mode.physicalWidth, mode.physicalHeight)
        if (w > 1920) {
            h = h * 1920 / w
            w = 1920
        }
        return "${w - w % 8}x${h - h % 8}"
    }
}

/** The external screen currently connected (updates when one is plugged in or removed). */
@Composable
fun rememberExternalDisplay(): Display? {
    val context = LocalContext.current
    var display by remember { mutableStateOf(ExternalDisplay.find(context)) }
    DisposableEffect(Unit) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { display = ExternalDisplay.find(context) }
            override fun onDisplayRemoved(id: Int) { display = ExternalDisplay.find(context) }
            override fun onDisplayChanged(id: Int) { display = ExternalDisplay.find(context) }
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    return display
}

/** Shows the desktop full screen on [display] while this is in composition. */
@Composable
fun ExternalDesktop(display: Display, client: VncClient, cursor: Cursor) {
    val activity = LocalContext.current.findComponentActivity() ?: return
    DisposableEffect(display.displayId, client) {
        val presentation = DesktopPresentation(activity, display, client, cursor)
        runCatching { presentation.show() }
        onDispose { runCatching { presentation.dismiss() } }
    }
}

private class DesktopPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val client: VncClient,
    private val cursor: Cursor,
) : Presentation(activity, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compose needs the owners of a host; borrow SpirOS's activity.
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(activity)
            it.setViewTreeViewModelStoreOwner(activity)
            it.setViewTreeSavedStateRegistryOwner(activity)
        }
        setContentView(
            ComposeView(context).apply {
                setViewTreeLifecycleOwner(activity)
                setViewTreeViewModelStoreOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                setContent {
                    VmScreen(client, Modifier.fillMaxSize().background(Color.Black), controls = false, cursor = cursor)
                }
            },
        )
    }
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}
