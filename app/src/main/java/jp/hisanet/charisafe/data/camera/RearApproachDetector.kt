package jp.hisanet.charisafe.data.camera

import android.media.ToneGenerator
import android.media.AudioManager
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class RearApproachDetector(
    private val onApproachDetected: () -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "RearApproachDetector"
        private const val HIGH_BRIGHTNESS_PIXEL_THRESHOLD = 200 // 明るさの閾値
        private const val HIGH_BRIGHTNESS_RATIO_TRIGGER = 0.03f // 3%以上が高輝度
        private const val AREA_EXPANSION_RATIO = 1.3f // 1.3倍以上の急拡大
    }

    private var previousHighBrightnessRatio = 0f
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
    private var lastAlertTimestamp = 0L

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val buffer = mediaImage.planes[0].buffer
            val data = buffer.toByteArray()
            val step = 4
            var totalLuminosity = 0L
            var highBrightnessPixelCount = 0
            var sampledCount = 0

            (data.indices step step).forEach { i ->
                val pixel = data[i].toInt() and 0xFF
                totalLuminosity += pixel
                sampledCount++

                if (pixel >= HIGH_BRIGHTNESS_PIXEL_THRESHOLD) {
                    highBrightnessPixelCount++
                }
            }

            val avgLuminosity = if (sampledCount > 0) totalLuminosity.toDouble() / sampledCount else 0.0
            val highBrightnessRatio = highBrightnessPixelCount.toFloat() / sampledCount

            val now = System.currentTimeMillis()
            // 簡易テストのため、夜間判定（暗所）または急激な高輝度領域の出現・拡大を検知
            if (highBrightnessRatio >= HIGH_BRIGHTNESS_RATIO_TRIGGER) {
                if (previousHighBrightnessRatio > 0f && highBrightnessRatio >= previousHighBrightnessRatio * AREA_EXPANSION_RATIO) {
                    if (now - lastAlertTimestamp > 10000L) { // 10秒間隔のクールダウン
                        Log.w(TAG, "🚨 後方車両接近検知! AvgLum: $avgLuminosity, Ratio: $highBrightnessRatio (Prev: $previousHighBrightnessRatio)")
                        lastAlertTimestamp = now
                        
                        // 1. 警告音再生
                        toneGenerator.startTone(ToneGenerator.TONE_DTMF_S, 600)
                        
                        // 2. 自動イベント保存発火
                        onApproachDetected()
                    }
                }
            }
            previousHighBrightnessRatio = highBrightnessRatio

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
