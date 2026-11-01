package com.spiros.app.linux

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The engine's one long background job (installing a Linux system or downloading an
 * AI model), with pause / resume / cancel. Work loops call [checkpoint] often; SpirOS
 * reads [state] through EngineService to show progress anywhere, even after leaving
 * the screen, and in the control notification.
 */
object BackgroundTask {

    data class State(
        /** "linux" or "model". */
        val kind: String,
        /** What is being installed / downloaded (desktop id or model file). */
        val name: String,
        /** DOWNLOADING, COPYING, VERIFYING, EXTRACTING, FINISHING. */
        val stage: String = "",
        val done: Long = 0,
        val total: Long = 0,
        val paused: Boolean = false,
        val active: Boolean = true,
        val error: String? = null,
    ) {
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    class Cancelled : Exception("Cancelled")

    @Volatile
    var state: State? = null
        private set

    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** Starts a job; false if another one is still running. */
    @Synchronized
    fun begin(kind: String, name: String): Boolean {
        if (state?.active == true) return false
        paused.set(false)
        cancelled.set(false)
        state = State(kind, name)
        return true
    }

    fun stage(stage: String, total: Long = 0) {
        state = state?.copy(stage = stage, done = 0, total = total)
    }

    fun progress(done: Long, total: Long) {
        state = state?.copy(done = done, total = total)
    }

    fun end(error: String?) {
        state = state?.copy(active = false, paused = false, error = error)
    }

    fun pause() {
        if (state?.active != true) return
        paused.set(true)
        state = state?.copy(paused = true)
    }

    fun resume() {
        paused.set(false)
        state = state?.copy(paused = false)
    }

    fun cancel() {
        cancelled.set(true)
        paused.set(false)
    }

    /** Dismiss a finished job's result. */
    fun clear() {
        if (state?.active != true) state = null
    }

    val isCancelled get() = cancelled.get()

    /** Throws [Cancelled] when cancelled; blocks here while paused. */
    fun checkpoint() {
        if (cancelled.get()) throw Cancelled()
        while (paused.get()) {
            Thread.sleep(250)
            if (cancelled.get()) throw Cancelled()
        }
    }
}
