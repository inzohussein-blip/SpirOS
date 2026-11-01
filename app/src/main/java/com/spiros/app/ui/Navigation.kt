package com.spiros.app.ui

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Destination requested from outside the UI (launcher shortcuts, Quick Settings tile). */
object Navigation {
    const val EXTRA = "com.spiros.app.extra.OPEN"
    const val VAULT = "vault"
    const val BROWSER = "browser"
    const val LINUX = "linux"
    const val SHIELD = "shield"

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA)?.let { _pending.value = it }
    }

    fun consume() {
        _pending.value = null
    }
}
