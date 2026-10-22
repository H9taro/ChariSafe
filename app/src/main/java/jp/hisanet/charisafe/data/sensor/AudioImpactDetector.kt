package jp.hisanet.charisafe.data.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs

class AudioImpactDetector(
    private val onLoudSoundDetected: () -> Unit
) {
    companion object {
        private const val TAG = "AudioImpactDetector"
        private const val SAMPLE_RATE = 8000
        private const val AMPLITUDE_THRESHOLD = 25000 // 大きな音・衝突音を想定した振幅閾値
    }

    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var listeningThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startListening(context: Context) {
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
                var lastTriggerTime = 0L

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

                        val now = System.currentTimeMillis()
                        if (maxAmplitude >= AMPLITUDE_THRESHOLD && now - lastTriggerTime > 10000L) {
                            lastTriggerTime = now
                            Log.w(TAG, "💥 [AudioImpact] 大きな音(衝突音)を検知! Amplitude: $maxAmplitude")
                            onLoudSoundDetected()
                        }
                    }
                }
            }.apply { start() }

            Log.i(TAG, "Started listening to microphone audio for impact/loud sound detection")
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
