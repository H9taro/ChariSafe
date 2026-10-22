package jp.hisanet.charisafe.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class PocketStorageDetector(
    context: Context,
    private val onPocketStateChanged: (Boolean) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "PocketStorageDetector"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var isRegistered = false

    var isPocketStored: Boolean = false
        private set

    fun startListening() {
        if (proximitySensor != null && !isRegistered) {
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
            isRegistered = true
            Log.i(TAG, "Started listening to proximity sensor for pocket detection")
        }
    }

    fun stopListening() {
        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
            Log.i(TAG, "Stopped listening to proximity sensor")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            // 近接センサーの最大レンジまたは通常「0.0」が近接状態 (ポケット内)
            val maxRange = proximitySensor?.maximumRange ?: 5.0f
            val isNear = distance < maxRange && distance == 0.0f // または一般的な近接判定

            if (isNear != isPocketStored) {
                isPocketStored = isNear
                Log.w(TAG, "👝 ポケット収納状態が変化しました: isPocketStored=$isPocketStored (distance=$distance)")
                onPocketStateChanged(isPocketStored)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
}
