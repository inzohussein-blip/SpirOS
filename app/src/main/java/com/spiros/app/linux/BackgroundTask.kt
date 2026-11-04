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
        /** Multi-part installs: part [step] of [steps], named [part] (e.g. "base", "mt5"). */
        val step: Int = 0,
        val steps: Int = 0,
        val part: String = "",
        /** Why it is held without being paused by the user, e.g. "wifi". */
        val waiting: String = "",
        /** Increases for every job, so SpirOS can tell finished jobs apart. */
        val id: Long = 0,
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
        state = State(kind, name, id = ++counter)
        return true
    }

    private var counter = 0L

    /** Jobs that finished (newest last), so SpirOS can announce them. */
    @Volatile
    var finished: List<State> = emptyList()
        private set

    /** When true, downloads wait (e.g. "only on Wi-Fi" while on mobile data). */
    @Volatile
    var holdReason: () -> String? = { null }

    fun steps(total: Int) {
        state = state?.copy(steps = total, step = 0)
    }

    fun step(index: Int, part: String) {
        state = state?.copy(step = index, part = part)
    }

    fun stage(stage: String, total: Long = 0) {
        state = state?.copy(stage = stage, done = 0, total = total)
    }

    fun progress(done: Long, total: Long) {
        state = state?.copy(done = done, total = total)
    }

    fun end(error: String?) {
        state = state?.copy(active = false, paused = false, waiting = "", error = error)
        state?.let { finished = (finished + it).takeLast(10) }
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
        var reason = holdReason()
        while (reason != null) {
            if (state?.waiting != reason) state = state?.copy(waiting = reason)
            Thread.sleep(1000)
            if (cancelled.get()) throw Cancelled()
            reason = holdReason()
        }
        if (state?.waiting?.isNotEmpty() == true) state = state?.copy(waiting = "")
    }
}
