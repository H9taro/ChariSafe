package jp.hisanet.charisafe.data.debug

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApproachDebugLogger(private val context: Context) {
    private val csvLogger = CsvDebugLogger(
        context = context,
        filePrefix = "approach_debug",
        header = "timestamp,avgLuminosity,currentRatio,smoothedRatio,expansionRate,ratioScore,expansionScore,approachScore,dynamicScoreThreshold,consecutiveCount,triggered,eventLogId"
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun log(
        avgLuminosity: Double,
        currentRatio: Float,
        smoothedRatio: Float,
        expansionRate: Float,
        ratioScore: Float,
        expansionScore: Float,
        approachScore: Float,
        dynamicScoreThreshold: Float,
        consecutiveCount: Int,
        triggered: Boolean,
        eventLogId: String = ""
    ) {
        val prefs = context.getSharedPreferences("charisafe_app_config", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("key_approach_csv_enabled", false)
        if (!isEnabled) return

        if (approachScore > 0f || consecutiveCount > 0 || triggered) {
            val timestamp = dateFormat.format(Date())
            val row = "$timestamp,$avgLuminosity,$currentRatio,$smoothedRatio,$expansionRate,$ratioScore,$expansionScore,$approachScore,$dynamicScoreThreshold,$consecutiveCount,$triggered,$eventLogId"
            csvLogger.appendRow(row)
        }
    }
}
