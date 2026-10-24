package jp.hisanet.charisafe.data.location

import android.util.Log
import jp.hisanet.charisafe.domain.model.GpxPoint

class SpeedDecelerationDetector(
    private val getTargetSeconds: () -> Float, // ユーザー設定の減速完了までの秒数 (例: 1.5秒)
    private val onDecelerationDetected: () -> Unit
) {
    companion object {
        private const val TAG = "SpeedDecelerationDetector"
        private const val MIN_SPEED_DROP_KMH = 8.0f // 8km/h以上の速度低下
    }

    private val speedHistory = mutableListOf<GpxPoint>()
    private var lastTriggerTimeMs = 0L

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
        val targetDurationMs = (getTargetSeconds() * 1000f).toLong().coerceIn(500L, 3000L)

        if (timeDeltaMs in 1L..targetDurationMs) {
            val speedDrop = oldest.speedKmh - latest.speedKmh

            if (speedDrop >= MIN_SPEED_DROP_KMH && now - lastTriggerTimeMs > 30000L) { // クールダウン30秒
                lastTriggerTimeMs = now
                Log.w(TAG, "🚨 急減速検知 (秒数指定: ${targetDurationMs}ms)! Drop: ${speedDrop}km/h in ${timeDeltaMs}ms")
                speedHistory.clear()
                onDecelerationDetected()
            }
        }
    }
}
