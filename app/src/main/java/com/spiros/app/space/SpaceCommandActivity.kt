package com.spiros.app.space

import android.app.Activity
import android.os.Bundle

/** Invisible entry point inside the space. Executes one authenticated command and exits. */
class SpaceCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = SpaceOwner.isProfileOwner(this) &&
            SpaceOwner.isAuthorized(this, intent) &&
            runCatching { SpaceOwner.execute(this, intent) }.getOrDefault(false)
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
