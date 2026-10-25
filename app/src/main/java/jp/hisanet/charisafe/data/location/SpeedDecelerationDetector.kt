package jp.hisanet.charisafe.data.location

import android.util.Log
import jp.hisanet.charisafe.data.debug.DecelerationDebugLogger
import jp.hisanet.charisafe.domain.model.GpxPoint

class SpeedDecelerationDetector(
    private val decelerationDebugLogger: DecelerationDebugLogger? = null,
    private val getTargetSeconds: () -> Float, // ユーザー設定の減速閾値までの秒数 (左=0.5s敏感, 右=3.0s鈍感)
    private val onDecelerationDetected: () -> Unit
) {
    constructor(getTargetSeconds: () -> Float, onDecelerationDetected: () -> Unit) : this(null, getTargetSeconds, onDecelerationDetected)

    companion object {
        private const val TAG = "SpeedDecelerationDetector"
        private const val MIN_SPEED_DROP_KMH = 8.0f // 8km/h以上の速度低下
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
        val targetDurationMs = (getTargetSeconds() * 1000f).toLong().coerceIn(500L, 3000L)

        val speedDrop = oldest.speedKmh - latest.speedKmh
        val triggered = timeDeltaMs in 1L..targetDurationMs && speedDrop >= MIN_SPEED_DROP_KMH

        decelerationDebugLogger?.log(
            oldestSpeedKmh = oldest.speedKmh,
            latestSpeedKmh = latest.speedKmh,
            speedDropKmh = speedDrop,
            timeDeltaMs = timeDeltaMs,
            targetDurationMs = targetDurationMs,
            targetSeconds = getTargetSeconds(),
            triggered = triggered
        )

        if (triggered) {
            Log.w(TAG, "🚨 [減速検知] 設定秒数(${targetDurationMs}ms)以内の急低下! Drop: ${speedDrop}km/h in ${timeDeltaMs}ms")
            speedHistory.clear()
            onDecelerationDetected()
        }
    }
}
