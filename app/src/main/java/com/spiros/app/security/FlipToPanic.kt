package com.spiros.app.security

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Optional: placing the phone face down on a table (screen against it) for about a
 * second while SpirOS is open triggers Panic. Only listens while SpirOS is visible.
 */
class FlipToPanic(private val activity: Activity) : SensorEventListener {
    private val sensors = activity.getSystemService(SensorManager::class.java)
    private var faceDownSince = 0L

    fun start() {
        if (!enabled(activity)) return
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val z = event.values[2]
        if (z < -8.5f) {
            if (faceDownSince == 0L) faceDownSince = SystemClock.elapsedRealtime()
            if (SystemClock.elapsedRealtime() - faceDownSince > 900) {
                stop()
                Panic.trigger(activity)
            }
        } else {
            faceDownSince = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val PREFS = "security"
        private const val KEY = "flip_panic"

        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setEnabled(context: Context, value: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}
