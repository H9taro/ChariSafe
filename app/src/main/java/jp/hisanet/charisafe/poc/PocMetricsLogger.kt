package jp.hisanet.charisafe.poc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PocMetrics(
    val timestamp: Long,
    val elapsedTimeSec: Long,
    val batteryLevel: Int,
    val batteryTempCelsius: Float,
    val javaHeapMb: Long,
    val nativeHeapMb: Long,
    val recordingStats: String = "",
    val errorCount: Long = 0
)

class PocMetricsLogger(private val context: Context, private val testName: String = "PoC-1") {

    companion object {
        private const val TAG = "ChariSafe-PoC"
    }

    private var csvFile: File? = null
    private var startTimeMs: Long = 0
    private var lastRecordedMetrics: PocMetrics? = null

    init {
        initCsvFile()
    }

    private fun initCsvFile() {
        try {
            val dir = File(context.getExternalFilesDir(null), "poc_logs")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            csvFile = File(dir, "poc_metrics_${testName}_$timeStamp.csv")

            FileWriter(csvFile, true).use { writer ->
                writer.append("timestamp,elapsed_sec,battery_level,battery_temp_c,java_heap_mb,native_heap_mb,details\n")
            }
            Log.i(TAG, "Initialized metrics log at: ${csvFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CSV log file", e)
        }
    }

    fun startSession() {
        startTimeMs = System.currentTimeMillis()
        Log.i(TAG, "=== Started PoC Metrics Session: $testName at ${Date(startTimeMs)} ===")
    }

    fun captureMetrics(details: String = ""): PocMetrics {
        val now = System.currentTimeMillis()
        val elapsedSec = if (startTimeMs > 0) (now - startTimeMs) / 1000 else 0

        // Battery
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryLevel = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val batteryTempCelsius = tempRaw / 10.0f

        // Memory
        val runtime = Runtime.getRuntime()
        val javaHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        val metrics = PocMetrics(
            timestamp = now,
            elapsedTimeSec = elapsedSec,
            batteryLevel = batteryLevel,
            batteryTempCelsius = batteryTempCelsius,
            javaHeapMb = javaHeapMb,
            nativeHeapMb = nativeHeapMb,
            recordingStats = details
        )
        lastRecordedMetrics = metrics

        logToLogcat(metrics)
        writeToCsv(metrics)

        return metrics
    }

    private fun logToLogcat(m: PocMetrics) {
        val logMsg = String.format(
            Locale.US,
            "[%s] Elapsed: %ds | Bat: %d%% (%.1f°C) | JavaHeap: %dMB | NativeHeap: %dMB | Details: %s",
            testName, m.elapsedTimeSec, m.batteryLevel, m.batteryTempCelsius, m.javaHeapMb, m.nativeHeapMb, m.recordingStats
        )
        Log.i(TAG, logMsg)
    }

    private fun writeToCsv(m: PocMetrics) {
        val file = csvFile ?: return
        try {
            FileWriter(file, true).use { writer ->
                val line = String.format(
                    Locale.US,
                    "%d,%d,%d,%.1f,%d,%d,\"%s\"\n",
                    m.timestamp, m.elapsedTimeSec, m.batteryLevel, m.batteryTempCelsius, m.javaHeapMb, m.nativeHeapMb, m.recordingStats
                )
                writer.append(line)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metrics to CSV", e)
        }
    }
}
