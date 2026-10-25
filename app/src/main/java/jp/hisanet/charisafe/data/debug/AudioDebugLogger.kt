package jp.hisanet.charisafe.data.debug

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioDebugLogger(private val context: Context) {
    private val csvLogger = CsvDebugLogger(
        context = context,
        filePrefix = "audio_debug",
        header = "timestamp,maxAmplitude,currentThreshold,soundSensitivity,triggered,eventLogId"
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun log(
        maxAmplitude: Int,
        currentThreshold: Int,
        soundSensitivity: Float,
        triggered: Boolean,
        eventLogId: String = ""
    ) {
        val prefs = context.getSharedPreferences("charisafe_app_config", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("key_audio_csv_enabled", false)
        if (!isEnabled) return

        if (maxAmplitude > 100) {
            val timestamp = dateFormat.format(Date())
            val row = "$timestamp,$maxAmplitude,$currentThreshold,$soundSensitivity,$triggered,$eventLogId"
            csvLogger.appendRow(row)
        }
    }
}
