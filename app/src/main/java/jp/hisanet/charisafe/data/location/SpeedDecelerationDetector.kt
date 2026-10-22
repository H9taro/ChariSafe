package jp.hisanet.charisafe.data.location

import android.util.Log
import jp.hisanet.charisafe.domain.model.GpxPoint

class SpeedDecelerationDetector(
    private val onDecelerationDetected: () -> Unit
) {
    companion object {
        private const val TAG = "SpeedDecelerationDetector"
        private const val MIN_INITIAL_SPEED_KMH = 15.0f // 15km/h以上から
        private const val MAX_FINAL_SPEED_KMH = 5.0f  // 5km/h未満へ
        private const val MAX_DURATION_MS = 3000L      // 3秒以内
    }

    private val speedHistory = mutableListOf<GpxPoint>()

    @Synchronized
    fun feedPoint(point: GpxPoint) {
        val now = point.timestampMs
        speedHistory.add(point)

        // 古い履歴（3秒以上前）を削除
        speedHistory.removeAll { now - it.timestampMs > 5000L }

        if (speedHistory.size < 2) return

        val oldest = speedHistory.first()
        val latest = speedHistory.last()

        val timeDeltaMs = latest.timestampMs - oldest.timestampMs
        if (timeDeltaMs in 1L..MAX_DURATION_MS) {
            val initialSpeed = oldest.speedKmh
            val finalSpeed = latest.speedKmh

            if (initialSpeed >= MIN_INITIAL_SPEED_KMH && finalSpeed < MAX_FINAL_SPEED_KMH) {
                Log.w(TAG, "🚨 急減速検知! Initial: ${initialSpeed}km/h -> Final: ${finalSpeed}km/h in ${timeDeltaMs}ms")
                speedHistory.clear()
                onDecelerationDetected()
            }
        }
    }
}
