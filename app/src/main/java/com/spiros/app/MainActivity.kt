package com.spiros.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spiros.app.ui.AppRoot
import com.spiros.app.ui.theme.SpirTheme

class MainActivity : ComponentActivity() {

    private val vault get() = (application as SpirApp).vault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording, or previews in the recents screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            SpirTheme {
                AppRoot(vault)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving SpirOS locks it, except while a system file picker we opened is in front.
        if (!isChangingConfigurations && !vault.holdLock) vault.lock()
    }
}
