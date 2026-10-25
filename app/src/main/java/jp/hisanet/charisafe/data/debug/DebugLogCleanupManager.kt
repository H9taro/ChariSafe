package jp.hisanet.charisafe.data.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object DebugLogCleanupManager {
    private const val TAG = "DebugLogCleanupManager"

    fun cleanOldLogs(context: Context) {
        try {
            val dir = context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug")
            if (!dir.exists() || !dir.isDirectory) return

            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -31)
            }
            val cutoffTime = cal.timeInMillis

            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)

            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".csv")) {
                    val parts = file.name.removeSuffix(".csv").split("_")
                    val dateStr = parts.lastOrNull()
                    if (dateStr != null && dateStr.length == 8) {
                        try {
                            val fileDate = sdf.parse(dateStr)
                            if (fileDate != null && fileDate.time < cutoffTime) {
                                val deleted = file.delete()
                                Log.i(TAG, "Deleted old debug log (${file.name}): $deleted")
                            }
                        } catch (e: Exception) {
                            // Ignored if filename doesn't match date pattern
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old debug logs", e)
        }
    }
}
