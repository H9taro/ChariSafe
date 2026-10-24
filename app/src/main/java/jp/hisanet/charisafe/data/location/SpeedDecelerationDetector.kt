package jp.hisanet.charisafe.data.location

import android.util.Log
import jp.hisanet.charisafe.domain.model.GpxPoint

class SpeedDecelerationDetector(
    private val onDecelerationDetected: () -> Unit
) {
    companion object {
        private const val TAG = "SpeedDecelerationDetector"
        private const val MAX_DURATION_MS = 3000L // 3秒以内
        private const val MIN_SPEED_DROP_KMH = 10.0f // 10km/h以上の急激な速度低下
    }

    private val speedHistory = mutableListOf<GpxPoint>()

    @Synchronized
    fun feedPoint(point: GpxPoint) {
        val now = point.timestampMs
        speedHistory.add(point)

        speedHistory.removeAll { now - it.timestampMs > 5000L }

        if (speedHistory.size < 2) return

        val oldest = speedHistory.first()
        val latest = speedHistory.last()

        val timeDeltaMs = latest.timestampMs - oldest.timestampMs
        if (timeDeltaMs in 1L..MAX_DURATION_MS) {
            val speedDrop = oldest.speedKmh - latest.speedKmh

            if (speedDrop >= MIN_SPEED_DROP_KMH) {
                Log.w(TAG, "🚨 急減速検知 (減速度重視)! Drop: ${speedDrop}km/h in ${timeDeltaMs}ms (From ${oldest.speedKmh} to ${latest.speedKmh})")
                speedHistory.clear()
                onDecelerationDetected()
            }
        }
    }
}
