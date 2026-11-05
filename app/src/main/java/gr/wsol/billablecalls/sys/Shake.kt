package gr.wsol.billablecalls.sys

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Shake the phone to report a bug: three strong shakes within a little more than a second, then
 * a pause of a few seconds before it can fire again. Only while the app is on screen.
 */
class Shake(private val onShake: () -> Unit) : SensorEventListener {
    private var firstAt = 0L
    private var count = 0
    private var firedAt = -COOLDOWN_MS
    private var sm: SensorManager? = null

    /** One reading, in g; true when this reading completes a shake. Kept apart from Android for the tests. */
    fun feed(g: Double, nowMs: Long): Boolean {
        if (g < THRESHOLD_G) return false
        if (count == 0 || nowMs - firstAt > WINDOW_MS) { firstAt = nowMs; count = 0 }
        count++
        if (count >= SHAKES && nowMs - firedAt > COOLDOWN_MS) { firedAt = nowMs; count = 0; return true }
        return false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        if (feed(sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH, e.timestamp / 1_000_000)) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun start(context: Context) {
        val m = context.getSystemService(SensorManager::class.java) ?: return
        val a = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm = m
        m.registerListener(this, a, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() { sm?.unregisterListener(this); sm = null }

    companion object {
        const val THRESHOLD_G = 2.6
        const val WINDOW_MS = 1_200L
        const val SHAKES = 3
        const val COOLDOWN_MS = 4_000L
    }
}
