package jp.hisanet.charisafe.data.sensor

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import jp.hisanet.charisafe.data.debug.AudioDebugLogger
import kotlin.math.abs

class AudioImpactDetector(
    private val audioDebugLogger: AudioDebugLogger? = null,
    private val getSoundSensitivityAmplitude: () -> Float, // ユーザー設定の振幅値 (例: 1000〜32767, デフォルト5000)
    private val onLoudSoundDetected: () -> Unit
) {
    constructor(getSoundSensitivityAmplitude: () -> Float, onLoudSoundDetected: () -> Unit) : this(null, getSoundSensitivityAmplitude, onLoudSoundDetected)

    companion object {
        private const val TAG = "AudioImpactDetector"
        private const val SAMPLE_RATE = 8000
    }

    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var listeningThread: Thread? = null

    @Volatile
    var isMuted: Boolean = false // 接近警告音鳴動中の一時ミュートフラグ (自作自演防止)

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isListening) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (bufferSize <= 0) return

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isListening = true

            listeningThread = Thread {
                val buffer = ShortArray(bufferSize / 2)

                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmplitude = 0
                        for (i in 0 until readSize) {
                            val amp = abs(buffer[i].toInt())
                            if (amp > maxAmplitude) {
                                maxAmplitude = amp
                            }
                        }

                        // ユーザー設定の振幅値 (最小: 1000、最大: マイク最大振幅値32767)
                        val currentAmplitudeSetting = getSoundSensitivityAmplitude().coerceIn(1000f, 32767f)
                        val thresholdAmplitude = currentAmplitudeSetting.toInt().coerceIn(1000, 32767)

                        val triggered = maxAmplitude >= thresholdAmplitude

                        audioDebugLogger?.log(
                            maxAmplitude = maxAmplitude,
                            currentThreshold = thresholdAmplitude,
                            soundSensitivity = currentAmplitudeSetting,
                            triggered = triggered
                        )

                        if (triggered && !isMuted) {
                            Log.w(TAG, "💥 [AudioImpact] 大音量検知! Amp: $maxAmplitude (Threshold: $thresholdAmplitude)")
                            onLoudSoundDetected()
                        }
                    }
                }
            }.apply { start() }

            Log.i(TAG, "Started listening to microphone audio with amplitude sensitivity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioImpactDetector", e)
        }
    }

    fun stopListening() {
        isListening = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
        listeningThread?.interrupt()
        listeningThread = null
        Log.i(TAG, "Stopped listening to microphone audio")
    }
}
