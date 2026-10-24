package jp.hisanet.charisafe.data.location

import android.util.Log
import jp.hisanet.charisafe.domain.model.GpxPoint

class SpeedDecelerationDetector(
    private val getSensitivity: () -> Float, // 感度スライダーから取得する減速にかかる秒数閾値 (例: 2.0秒)
    private val onDecelerationDetected: () -> Unit
) {
    companion object {
        private const val TAG = "SpeedDecelerationDetector"
        private const val MIN_DROP_SPEED_KMH = 10.0f // 10km/h以上の速度差
    }

    private val speedHistory = mutableListOf<GpxPoint>()

    @Synchronized
    fun feedPoint(point: GpxPoint) {
        val now = point.timestampMs
        speedHistory.add(point)

        // 過去5秒間の履歴を保持
        speedHistory.removeAll { now - it.timestampMs > 5000L }

        if (speedHistory.size < 2) return

        val oldest = speedHistory.first()
        val latest = speedHistory.last()

        val timeDeltaMs = latest.timestampMs - oldest.timestampMs
        val targetSeconds = getSensitivity() // 例: 2.0秒以内
        val maxDurationMs = (targetSeconds * 1000f).toLong().coerceIn(1000L, 5000L)

        if (timeDeltaMs in 1L..maxDurationMs) {
            val initialSpeed = oldest.speedKmh
            val finalSpeed = latest.speedKmh
            val dropSpeed = initialSpeed - finalSpeed

            // 巡行速度から設定秒数以内に10km/h以上急低下、かつ5km/h未満へ
            if (initialSpeed >= 15.0f && dropSpeed >= MIN_DROP_SPEED_KMH && finalSpeed < 5.0f) {
                Log.w(TAG, "🚨 急減速検知! Initial: ${initialSpeed}km/h -> Final: ${finalSpeed}km/h (Drop: ${dropSpeed}km/h) in ${timeDeltaMs}ms (Target: ${targetSeconds}s)")
                speedHistory.clear()
                onDecelerationDetected()
            }
        }
    }
}
