package com.spiros.app.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.util.Rational
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Picture-in-picture for the Linux desktop: leaving SpirOS shrinks the live desktop
 * into a corner window (Android's own PiP, no "draw over apps" permission needed).
 */
object Pip {
    /** True while the full-screen Linux desktop is on screen. */
    @Volatile
    var desktopVisible = false

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(value: Boolean) {
        _active.value = value
    }

    fun enter(activity: Activity): Boolean = runCatching {
        activity.enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
        )
    }.getOrDefault(false)
}
