package jp.hisanet.charisafe.data.debug

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.util.FileUtils
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

enum class TraceMetric {
    APPROACH,
    DECELERATION,
    COLLISION,
    SOUND,

    RECORDING_STATE,
    SEGMENT
}

enum class TraceLayer {
    DETECTOR,
    SERVICE,
    REPOSITORY,
    VIEWMODEL,
    UI,
    SYSTEM
}

class TraceLogger private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TraceLogger"

        private var instance: TraceLogger? = null

        fun getInstance(context: Context): TraceLogger {
            return instance ?: synchronized(this) {
                instance ?: TraceLogger(
                    context.applicationContext
                ).also {
                    instance = it
                }
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val sequenceNo = AtomicLong(1L)
    @Volatile
    private var currentSessionId: String = "SESSION_IDLE"

    // ノイズ抑制用の記録マップ
    private val lastValues = ConcurrentHashMap<String, Float>()
    private val lastTimes = ConcurrentHashMap<String, Long>()

    // 将来的な機能拡張フラグ
    @Volatile
    var isEnabled: Boolean = true

    fun startSession(sessionId: String) {
        currentSessionId = sessionId
        log(
            metric = TraceMetric.RECORDING_STATE,
            layer = TraceLayer.SYSTEM,
            value = 1.0f,
            extra = "START_SESSION",
            force = true
        )
    }

    fun endSession() {
        log(
            metric = TraceMetric.RECORDING_STATE,
            layer = TraceLayer.SYSTEM,
            value = 0.0f,
            extra = "END_SESSION",
            force = true
        )
        currentSessionId = "SESSION_IDLE"
    }

    fun logProtectionTrace(entry: Any) {
        log(
            metric = TraceMetric.SEGMENT,
            layer = TraceLayer.SYSTEM,
            value = 1.0f,
            extra = entry.toString(),
            force = true
        )
    }

    fun log(
        metric: TraceMetric,
        layer: TraceLayer,
        value: Float,
        extra: String? = null,
        force: Boolean = false
    ) {
        if (!isEnabled) return

        val now = System.currentTimeMillis()
        val key = "${metric.name}_${layer.name}"

        if (!force && !shouldLog(metric, key, value, now, extra)) {
            return
        }

        // 状態更新
        lastValues[key] = value
        lastTimes[key] = now

        val seq = sequenceNo.getAndIncrement()
        val sessionId = currentSessionId

        executor.execute {
            try {
                writeLogLine(now, sessionId, seq, metric, layer, value, extra)
            } catch (e: Exception) {
                Log.e(TAG, "TraceLogger write error", e)
            }
        }
    }

    private fun shouldLog(
        metric: TraceMetric,
        key: String,
        newValue: Float,
        now: Long,
        extra: String?
    ): Boolean {
        if (extra != null) return true

        val lastVal = lastValues[key] ?: return true
        val lastTime = lastTimes[key] ?: return true

        // 2秒ハートビート
        if (now - lastTime >= 2000L) {
            return true
        }

        // 変化量判定 (Metric別)
        val threshold = when (metric) {
            TraceMetric.APPROACH -> 1.0f
            TraceMetric.DECELERATION -> 0.1f
            TraceMetric.COLLISION -> 0.1f
            TraceMetric.SOUND -> 0.01f
            else -> 0f
        }

        return abs(newValue - lastVal) >= threshold
    }

    private fun writeLogLine(
        timestampMs: Long,
        sessionId: String,
        seqNo: Long,
        metric: TraceMetric,
        layer: TraceLayer,
        value: Float,
        extra: String?
    ) {
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestampMs))
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        val seqFormatted = String.format(Locale.US, "#%08d", seqNo)

        val debugDir = FileUtils.getDebugDir(context)
        val logFile = File(debugDir, "trace_$dateStr.log")

        val extraPart = if (extra != null) " | $extra" else ""
        val line = "$timeStr | $sessionId | $seqFormatted | ${metric.name} | ${layer.name} | ${String.format(Locale.US, "%.6f", value)}$extraPart"

        BufferedWriter(FileWriter(logFile, true)).use { writer ->
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }
}
