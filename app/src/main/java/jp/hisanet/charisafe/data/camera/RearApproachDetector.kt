package jp.hisanet.charisafe.data.camera

import android.media.ToneGenerator
import android.media.AudioManager
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import jp.hisanet.charisafe.data.debug.ApproachDebugLogger
import jp.hisanet.charisafe.data.debug.TraceLayer
import jp.hisanet.charisafe.data.debug.TraceLogger
import jp.hisanet.charisafe.data.debug.TraceMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RearApproachDetector(
    private val approachDebugLogger: ApproachDebugLogger? = null,
    private val getApproachSensitivity: () -> Float = { 0.7f },
    private val onApproachDetected: () -> Unit
) : ImageAnalysis.Analyzer {
    private lateinit var traceLogger: TraceLogger
    constructor(getApproachSensitivity: () -> Float, onApproachDetected: () -> Unit) : this(null, getApproachSensitivity, onApproachDetected)
    constructor(onApproachDetected: () -> Unit) : this(null, { 0.7f }, onApproachDetected)

    companion object {
        private const val TAG = "RearApproachDetector"
        private const val HIGH_BRIGHTNESS_PIXEL_THRESHOLD = 200
        private const val REQUIRED_CONSECUTIVE_FRAMES = 4 // 連続成立フレーム数
    }

    private val _approachScoreState = MutableStateFlow(0f)
    val approachScoreState: StateFlow<Float> = _approachScoreState.asStateFlow()

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private val ratioHistory = FloatArray(5)
    private var historyIndex = 0
    private var consecutiveCount = 0

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val plane = mediaImage.planes[0]
            val buffer = plane.buffer
            val width = mediaImage.width
            val height = mediaImage.height
            val rowStride = plane.rowStride
            val data = buffer.toByteArray()

            // 1. 画面中央領域 (ROI): 水平 30%〜70%, 垂直 40%〜80%
            val startX = (width * 0.3).toInt()
            val endX = (width * 0.7).toInt()
            val startY = (height * 0.4).toInt()
            val endY = (height * 0.8).toInt()

            var totalLuminosity = 0L
            var highBrightnessPixelCount = 0
            var sampledCount = 0
            val step = 4

            for (y in startY until endY step step) {
                val rowOffset = y * rowStride
                for (x in startX until endX step step) {
                    if (rowOffset + x < data.size) {
                        val pixel = data[rowOffset + x].toInt() and 0xFF
                        totalLuminosity += pixel
                        sampledCount++
                        if (pixel >= HIGH_BRIGHTNESS_PIXEL_THRESHOLD) {
                            highBrightnessPixelCount++
                        }
                    }
                }
            }

            val avgLuminosity = if (sampledCount > 0) totalLuminosity.toDouble() / sampledCount else 0.0
            val currentRatio = if (sampledCount > 0) highBrightnessPixelCount.toFloat() / sampledCount else 0f

            // 3. 移動平均による平滑化
            ratioHistory[historyIndex] = currentRatio
            historyIndex = (historyIndex + 1) % ratioHistory.size
            val smoothedRatio = ratioHistory.average().toFloat()

            // 前フレームとの比較による拡大率
            val prevRatio = ratioHistory[(historyIndex - 2 + ratioHistory.size) % ratioHistory.size]
            val expansionRate = if (prevRatio > 0.001f) smoothedRatio / prevRatio else 1.0f

            // 4. 接近スコア計算 (高輝度率 + 拡大率ボーナス)
            val ratioScore = (smoothedRatio * 100f).coerceAtMost(50f) // 最大50点
            val expansionScore = if (expansionRate > 1.2f) ((expansionRate - 1.0f) * 50f).coerceAtMost(50f) else 0f // 最大50点
            val approachScore = ratioScore + expansionScore

            // リアルタイム StateFlow の更新 (I/Fトレーサビリティ)
            _approachScoreState.value = approachScore
            Log.d(TAG, "📊 [Trace] approachScore: $approachScore")

            // 接近感度スライダー連動 (30%敏感=38.5 〜 100%鈍感=70.0)
            val userSensitivity = getApproachSensitivity().coerceIn(0.3f, 1.0f)
            val dynamicScoreThreshold = 25.0f + (userSensitivity * 45.0f)

            val triggered = (approachScore >= dynamicScoreThreshold) && (consecutiveCount >= REQUIRED_CONSECUTIVE_FRAMES)

            approachDebugLogger?.log(
                avgLuminosity = avgLuminosity,
                currentRatio = currentRatio,
                smoothedRatio = smoothedRatio,
                expansionRate = expansionRate,
                ratioScore = ratioScore,
                expansionScore = expansionScore,
                approachScore = approachScore,
                dynamicScoreThreshold = dynamicScoreThreshold,
                consecutiveCount = consecutiveCount,
                triggered = triggered
            )
            traceLogger.log(TraceMetric.APPROACH, TraceLayer.DETECTOR, approachScore)
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            Log.i(
                "ApproachTrace",
                "$timeStr\nDetector=$approachScore"
            )

            // 5. 連続成立フレーム判定 & スコア判定
            if (approachScore >= dynamicScoreThreshold) {
                consecutiveCount++
                Log.d(TAG, "🎯 接近候補成立 [Count: $consecutiveCount/$REQUIRED_CONSECUTIVE_FRAMES] Score: $approachScore (Th: $dynamicScoreThreshold)")

                if (consecutiveCount >= REQUIRED_CONSECUTIVE_FRAMES) {
                    Log.w(TAG, "🚨 後方車両接近検知確定! Score: $approachScore (Th: $dynamicScoreThreshold), AvgLum: $avgLuminosity")
                    consecutiveCount = 0

                    toneGenerator.startTone(ToneGenerator.TONE_DTMF_S, 600)
                    onApproachDetected()
                }
            } else {
                if (consecutiveCount > 0) consecutiveCount--
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image frame for approach", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        val data = ByteArray(remaining())
        get(data)
        return data
    }
}
