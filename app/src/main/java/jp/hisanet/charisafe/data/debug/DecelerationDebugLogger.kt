package jp.hisanet.charisafe.data.debug

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DecelerationDebugLogger(private val context: Context) {
    private val csvLogger = CsvDebugLogger(
        context = context,
        filePrefix = "deceleration_debug",
        header = "timestamp,oldestSpeedKmh,latestSpeedKmh,speedDropKmh,timeDeltaMs,targetDurationMs,targetSeconds,triggered,eventLogId"
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun log(
        oldestSpeedKmh: Float,
        latestSpeedKmh: Float,
        speedDropKmh: Float,
        timeDeltaMs: Long,
        targetDurationMs: Long,
        targetSeconds: Float,
        triggered: Boolean,
        eventLogId: String = ""
    ) {
        val prefs = context.getSharedPreferences("charisafe_app_config", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("key_deceleration_csv_enabled", false)
        if (!isEnabled) return

        if (speedDropKmh > 1.0f || triggered) {
            val timestamp = dateFormat.format(Date())
            val row = "$timestamp,$oldestSpeedKmh,$latestSpeedKmh,$speedDropKmh,$timeDeltaMs,$targetDurationMs,$targetSeconds,$triggered,$eventLogId"
            csvLogger.appendRow(row)
        }
    }
}
