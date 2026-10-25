package jp.hisanet.charisafe.data.sensor

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs

class AudioImpactDetector(
    private val getSoundSensitivityDb: () -> Float, // ユーザー設定の dB 値 (例: 70dB〜100dB, デフォルト85dB)
    private val onLoudSoundDetected: () -> Unit
) {
    companion object {
        private const val TAG = "AudioImpactDetector"
        private const val SAMPLE_RATE = 8000
    }

    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var listeningThread: Thread? = null

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

                        // ユーザー設定の dB 値をリニア振幅閾値に変換 (例: 70dB=敏感, 100dB=鈍感)
                        // dBから振幅への簡易換算: 100dBを最高値(32767)とし、設定dBに応じた閾値を算出
                        val currentDbSetting = getSoundSensitivityDb().coerceIn(70f, 100f)
                        // 設定dBが低いほど少ない音量(低い振幅)で検知する (例: 70dB設定なら約1000の振幅で反応)
                        val thresholdAmplitude = (32767.0 * (currentDbSetting - 60.0) / 40.0).toInt().coerceIn(500, 32000)

                        val now = System.currentTimeMillis()
                        if (maxAmplitude >= thresholdAmplitude && now - lastTriggerTime > 15000L) { // クールダウン15秒
                            lastTriggerTime = now
                            Log.w(TAG, "💥 [AudioImpact] 大音量検知! Amp: $maxAmplitude (Threshold: $thresholdAmplitude for ${currentDbSetting}dB)")
                            onLoudSoundDetected()
                        }
                    }
                }
            }.apply { start() }

            Log.i(TAG, "Started listening to microphone audio with dynamic dB sensitivity")
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
