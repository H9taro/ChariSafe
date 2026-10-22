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
        private const val DARK_LUMINOSITY_THRESHOLD = 50.0 // 夜間判定用平均輝度上限
        private const val HIGH_BRIGHTNESS_PIXEL_THRESHOLD = 220 // ヘッドライトの明るさ閾値
        private const val HIGH_BRIGHTNESS_RATIO_TRIGGER = 0.05f // 画面の5%以上が高輝度
        private const val AREA_EXPANSION_RATIO = 1.5f // 前回比1.5倍以上の急拡大
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
            val width = mediaImage.width
            val height = mediaImage.height

            // 1. 平均輝度の計算 (ダウンサンプルして高速化)
            var totalLuminosity = 0L
            var highBrightnessPixelCount = 0
            val step = 4 // 間引きサンプリング
            var sampledCount = 0

            for (i in 0 until data.size step step) {
                val pixel = data[i].toInt() and 0xFF
                totalLuminosity += pixel
                sampledCount++

                if (pixel >= HIGH_BRIGHTNESS_PIXEL_THRESHOLD) {
                    highBrightnessPixelCount++
                }
            }

            val avgLuminosity = if (sampledCount > 0) totalLuminosity.toDouble() / sampledCount else 0.0
            val highBrightnessRatio = highBrightnessPixelCount.toFloat() / sampledCount

            // 2. 夜間判定かつ高輝度領域の急拡大判定
            val now = System.currentTimeMillis()
            if (avgLuminosity <= DARK_LUMINOSITY_THRESHOLD) {
                if (highBrightnessRatio >= HIGH_BRIGHTNESS_RATIO_TRIGGER) {
                    if (previousHighBrightnessRatio > 0f && highBrightnessRatio >= previousHighBrightnessRatio * AREA_EXPANSION_RATIO) {
                        if (now - lastAlertTimestamp > 5000L) { // 5秒間隔のクールダウン
                            Log.w(TAG, "🚨 夜間後方車両接近検知! AvgLum: $avgLuminosity, Ratio: $highBrightnessRatio (Prev: $previousHighBrightnessRatio)")
                            lastAlertTimestamp = now
                            
                            // 警告音再生
                            toneGenerator.startTone(ToneGenerator.TONE_DTMF_S, 500)
                            
                            // 自動イベント保存発火
                            onApproachDetected()
                        }
                    }
                }
            }
            previousHighBrightnessRatio = highBrightnessRatio

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image frame for rear approach", e)
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
