package com.spiros.app.ui.space

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** The Activity behind a Compose context (themes may wrap it). */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Same as [findActivity], for call sites outside this package. */
fun Context.findActivityOrNull(): Activity? = findActivity()
