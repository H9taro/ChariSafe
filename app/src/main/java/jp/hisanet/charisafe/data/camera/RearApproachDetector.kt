package jp.hisanet.charisafe.data.camera

import android.media.AudioManager
import android.media.ToneGenerator
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
import kotlin.math.sqrt

class RearApproachDetector(
    private val approachDebugLogger: ApproachDebugLogger? = null,
    private val getApproachSensitivity: () -> Float = { 100f },
    private val onApproachDetected: () -> Unit
) : ImageAnalysis.Analyzer {
    private lateinit var traceLogger: TraceLogger
    constructor(getApproachSensitivity: () -> Float, onApproachDetected: () -> Unit) : this(null, getApproachSensitivity, onApproachDetected)
    constructor(onApproachDetected: () -> Unit) : this(null, { 100f }, onApproachDetected)

    companion object {
        private const val TAG = "RearApproachDetector"
        private const val HIGH_BRIGHTNESS_PIXEL_VALUE = 0xFF
        private const val BRIGHTNESS_MARGIN = 0x38
        private const val REQUIRED_CONSECUTIVE_FRAMES = 3 // 連続成立フレーム数
    }

    private val _approachScoreState = MutableStateFlow(0f)
    val approachScoreState: StateFlow<Float> = _approachScoreState.asStateFlow()

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private val ratioHistory = FloatArray(REQUIRED_CONSECUTIVE_FRAMES)
    private var historyIndex = 0

    private var frameCounter = 0

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

            // 1. 画面ピクセル値集計
            val startX = 0
            val startY = 0

            var totalLuminosity = 0L
            var highBrightnessPixelCount = 0
            var sampledCount = 0
            val step = 4
            var maxLuminosity = 0

            for (y in startY until height step step) {
                val rowOffset = y * rowStride
                for (x in startX until width step step) {
                    if (rowOffset + x < data.size) {
                        val pixel = data[rowOffset + x].toInt() and HIGH_BRIGHTNESS_PIXEL_VALUE
                        totalLuminosity += pixel
                        maxLuminosity += HIGH_BRIGHTNESS_PIXEL_VALUE
                        sampledCount++
                        if (pixel >= HIGH_BRIGHTNESS_PIXEL_VALUE - BRIGHTNESS_MARGIN) {
                            highBrightnessPixelCount++
                        }
                    }
                }
            }

            val luminosityRatio = totalLuminosity.toDouble() / maxLuminosity.toDouble()
            val currentRatio = sqrt(highBrightnessPixelCount.toDouble()).toFloat()

            // 3. 移動平均による平滑化
            ratioHistory[historyIndex] = currentRatio
            historyIndex = (historyIndex + 1) % ratioHistory.size
            val smoothedRatio = ratioHistory.average().toFloat()

            // 前フレームとの比較による拡大率
            val expansionRate = currentRatio - smoothedRatio

            // 4. 接近スコア計算
            val darkness = (1.0 - luminosityRatio)
            val approachScore = (expansionRate * darkness * darkness).toFloat()

            // 接近感度スライダー連動
            val userSensitivity = getApproachSensitivity()

            var triggered = false

            // リアルタイム StateFlow の更新 (I/Fトレーサビリティ)
            _approachScoreState.value = approachScore
            Log.d(TAG, "📊 [Trace] approachScore: $approachScore (Th: $userSensitivity)")

            // 5. スコア & 連続成立フレーム判定
            if (approachScore >= userSensitivity && frameCounter-- <= 0) {
                Log.w(
                    TAG,
                    "🚨 後方車両接近検知! Score: $approachScore (Th: $userSensitivity), AvgLum: $luminosityRatio"
                )
                triggered = true
                frameCounter = REQUIRED_CONSECUTIVE_FRAMES
                toneGenerator.startTone(ToneGenerator.TONE_DTMF_S, 600)
                onApproachDetected()
            }
            approachDebugLogger?.log(
                luminosityRatio = luminosityRatio,
                currentRatio = currentRatio,
                smoothedRatio = smoothedRatio,
                expansionRate = expansionRate,
                approachScore = approachScore,
                approachSensitivity = userSensitivity,
                triggered = triggered
            )
            traceLogger.log(TraceMetric.APPROACH, TraceLayer.DETECTOR, approachScore)
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            Log.i(
                "ApproachTrace",
                "$timeStr\nDetector=$approachScore"
            )

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
