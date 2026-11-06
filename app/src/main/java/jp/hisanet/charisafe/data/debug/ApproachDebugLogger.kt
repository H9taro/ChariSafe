package jp.hisanet.charisafe.data.debug

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApproachDebugLogger(private val context: Context) {
    private val csvLogger = CsvDebugLogger(
        context = context,
        filePrefix = "approach_debug",
        header = "timestamp,avgLuminosity,activeBlockRatio,maxClusterArea,clusterDensity,clusterAspect,wOccupancy,wDensity,wAspect,rawScore,areaExpansion,effectiveExpansion,emaScore,approachSensitivity,triggered,eventLogId"
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun log(
        luminosityRatio: Double,
        activeBlockRatio: Float,
        maxClusterArea: Int,
        clusterDensity: Float,
        clusterAspect: Float,
        wOccupancy: Float,
        wDensity: Float,
        wAspect: Float,
        rawScore: Float,
        areaExpansion: Int,
        effectiveExpansion: Int,
        emaScore: Float,
        approachSensitivity: Float,
        triggered: Boolean,
        eventLogId: String = ""
    ) {
        val prefs = context.getSharedPreferences("charisafe_app_config", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("key_approach_csv_enabled", false)
        if (!isEnabled) return

        if (emaScore > 0f || triggered) {
            val timestamp = dateFormat.format(Date())
            val row = "$timestamp,$luminosityRatio,$activeBlockRatio,$maxClusterArea,$clusterDensity,$clusterAspect,$wOccupancy,$wDensity,$wAspect,$rawScore,$areaExpansion,$effectiveExpansion,$emaScore,$approachSensitivity,$triggered,$eventLogId"
            csvLogger.appendRow(row)
        }
    }
}
