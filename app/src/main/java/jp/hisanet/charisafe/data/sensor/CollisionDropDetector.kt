package jp.hisanet.charisafe.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import jp.hisanet.charisafe.data.debug.CollisionDebugLogger
import kotlin.math.sqrt

class CollisionDropDetector(
    context: Context,
    private val collisionDebugLogger: CollisionDebugLogger? = null,
    private val onCollisionOrDropDetected: () -> Unit
) : SensorEventListener {

    constructor(context: Context, onCollisionOrDropDetected: () -> Unit) : this(context, null, onCollisionOrDropDetected)

    companion object {
        private const val TAG = "CollisionDropDetector"
        private const val SHOCK_THRESHOLD_G = 3.0f // 3.0G以上の衝撃で衝突/落下と判定
        private const val GRAVITY_G = 9.81f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var isRegistered = false
    private var lastTriggerTime = 0L

    fun startListening() {
        if (accelerometer != null && !isRegistered) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            isRegistered = true
            Log.i(TAG, "Started listening to accelerometer for collision/drop detection")
        }
    }

    fun stopListening() {
        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
            Log.i(TAG, "Stopped listening to accelerometer")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // 合成加速度 (G換算)
            val acceleration = sqrt((x * x + (y * y).toDouble() + (z * z)).toDouble()).toFloat()
            val gForce = acceleration / GRAVITY_G

            val now = System.currentTimeMillis()
            val triggered = gForce >= SHOCK_THRESHOLD_G && now - lastTriggerTime > 10000L

            collisionDebugLogger?.log(
                gForce = gForce,
                shockThresholdG = SHOCK_THRESHOLD_G,
                triggered = triggered
            )

            if (triggered) {
                lastTriggerTime = now
                Log.w(TAG, "💥 衝突・落下検知! G-Force: ${String.format(java.util.Locale.US, "%.2f", gForce)}G")
                onCollisionOrDropDetected()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
}
