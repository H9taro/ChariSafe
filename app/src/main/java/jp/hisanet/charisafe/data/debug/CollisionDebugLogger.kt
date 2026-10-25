package jp.hisanet.charisafe.data.debug

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CollisionDebugLogger(private val context: Context) {
    private val csvLogger = CsvDebugLogger(
        context = context,
        filePrefix = "collision_debug",
        header = "timestamp,gForce,shockThresholdG,triggered,eventLogId"
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun log(
        gForce: Float,
        shockThresholdG: Float,
        triggered: Boolean,
        eventLogId: String = ""
    ) {
        val prefs = context.getSharedPreferences("charisafe_app_config", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("key_collision_csv_enabled", false)
        if (!isEnabled) return

        if (gForce > 1.5f || triggered) {
            val timestamp = dateFormat.format(Date())
            val row = "$timestamp,$gForce,$shockThresholdG,$triggered,$eventLogId"
            csvLogger.appendRow(row)
        }
    }
}
