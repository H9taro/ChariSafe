package jp.hisanet.charisafe.data.location

import android.util.Log
import jp.hisanet.charisafe.data.debug.DecelerationDebugLogger
import jp.hisanet.charisafe.domain.model.GpxPoint

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpeedDecelerationDetector(
    private val decelerationDebugLogger: DecelerationDebugLogger? = null,
    private val getTargetDecelerationMs2: () -> Float, // ユーザー設定の減速閾値 (m/s^2, 例: 1.0 〜 4.0)
    private val onDecelerationDetected: () -> Unit
) {
    constructor(getTargetDecelerationMs2: () -> Float, onDecelerationDetected: () -> Unit) : this(null, getTargetDecelerationMs2, onDecelerationDetected)

    companion object {
        private const val TAG = "SpeedDecelerationDetector"
        private const val ALPHA_EMA = 0.35f // 指数移動平均(EMA)平滑化係数
    }

    private val _decelerationState = MutableStateFlow(0f)
    val decelerationState: StateFlow<Float> = _decelerationState.asStateFlow()

    private var smoothedSpeedMs: Float = -1f
    private var lastTimestampMs: Long = 0L
    private var consecutiveCount: Int = 0

    @Synchronized
    fun feedPoint(point: GpxPoint) {
        val now = point.timestampMs
        val rawSpeedMs = point.speedKmh / 3.6f // km/h -> m/s

        if (smoothedSpeedMs < 0f) {
            smoothedSpeedMs = rawSpeedMs
            lastTimestampMs = now
            return
        }

        val dt = (now - lastTimestampMs) / 1000f // 秒
        if (dt <= 0.05f) return // タイムスタンプが近すぎる場合はスキップ

        // 1. 速度の EMA 平滑化
        val prevSmoothed = smoothedSpeedMs
        smoothedSpeedMs = ALPHA_EMA * rawSpeedMs + (1f - ALPHA_EMA) * prevSmoothed

        // 2. 減速度の算出 (a = (v_prev - v_curr) / dt  [m/s^2])
        val deceleration = (prevSmoothed - smoothedSpeedMs) / dt
        val thresholdMs2 = getTargetDecelerationMs2().coerceIn(1.0f, 4.0f)

        // リアルタイム StateFlow の更新 (I/Fトレーサビリティ)
        _decelerationState.value = deceleration.coerceAtLeast(0f)
        Log.d(TAG, "📊 [Trace] deceleration: ${deceleration.coerceAtLeast(0f)} m/s^2")

        // 3. 継続判定（連続2回以上の超過でノイズカット）
        val isExceeding = deceleration >= thresholdMs2
        if (isExceeding) {
            consecutiveCount++
        } else {
            consecutiveCount = (consecutiveCount - 1).coerceAtLeast(0)
        }

        val triggered = consecutiveCount >= 2

        decelerationDebugLogger?.log(
            oldestSpeedKmh = prevSmoothed * 3.6f,
            latestSpeedKmh = smoothedSpeedMs * 3.6f,
            speedDropKmh = deceleration,
            timeDeltaMs = (dt * 1000f).toLong(),
            targetDurationMs = (thresholdMs2 * 1000f).toLong(),
            targetSeconds = thresholdMs2,
            triggered = triggered
        )

        lastTimestampMs = now

        if (triggered) {
            Log.w(TAG, "🚨 [減速検知] 加速度 ${String.format(java.util.Locale.US, "%.2f", deceleration)} m/s^2 (閾値: ${thresholdMs2} m/s^2) を連続検知!")
            consecutiveCount = 0
            onDecelerationDetected()
        }
    }
}
