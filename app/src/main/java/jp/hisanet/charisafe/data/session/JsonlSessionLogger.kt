package jp.hisanet.charisafe.data.session

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.util.FileUtils
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JsonlSessionLogger(private val context: Context) {
    companion object {
        private const val TAG = "JsonlSessionLogger"
    }

    private var sessionFile: File? = null
    private var fileWriter: FileWriter? = null
    private var isLogging = false

    @Synchronized
    fun startSession(sessionId: String) {
        if (isLogging) return
        try {
            val logsDir = FileUtils.getLogsDir(context)
            if (!logsDir.exists()) logsDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(logsDir, "session_${timestamp}.jsonl")
            sessionFile = file
            fileWriter = FileWriter(file, true)

            val startJson = "{\"t\":\"START\",\"ts\":${System.currentTimeMillis()},\"sid\":\"$sessionId\"}"
            fileWriter?.appendLine(startJson)
            fileWriter?.flush()
            isLogging = true
            Log.i(TAG, "Started JSONL session logging to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start JSONL session logging", e)
        }
    }

    @Synchronized
    fun logStateTick(state: String) {
        if (!isLogging) return
        try {
            val json = "{\"t\":\"STATE_TICK\",\"ts\":${System.currentTimeMillis()},\"st\":\"$state\"}"
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log STATE_TICK", e)
        }
    }

    @Synchronized
    fun logGpxFix(lat: Double, lon: Double, ele: Double, spd: Float) {
        if (!isLogging) return
        try {
            val json = "{\"t\":\"GPS_FIX\",\"ts\":${System.currentTimeMillis()},\"lat\":$lat,\"lon\":$lon,\"ele\":$ele,\"spd\":$spd}"
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log GPS_FIX", e)
        }
    }

    @Synchronized
    fun logGpxLost(reason: String) {
        if (!isLogging) return
        try {
            val json = "{\"t\":\"GPS_LOST\",\"ts\":${System.currentTimeMillis()},\"reason\":\"$reason\"}"
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log GPS_LOST", e)
        }
    }

    @Synchronized
    fun logGpxRestored(lat: Double, lon: Double, ele: Double, spd: Float) {
        if (!isLogging) return
        try {
            val json = "{\"t\":\"GPS_RESTORED\",\"ts\":${System.currentTimeMillis()},\"lat\":$lat,\"lon\":$lon,\"ele\":$ele,\"spd\":$spd}"
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log GPS_RESTORED", e)
        }
    }

    @Synchronized
    fun logEvent(eventType: String, value: Float, threshold: Float, spd: Float, lat: Double, lon: Double, segmentId: String) {
        if (!isLogging) return
        try {
            val json = "{\"t\":\"$eventType\",\"ts\":${System.currentTimeMillis()},\"val\":$value,\"th\":$threshold,\"spd\":$spd,\"lat\":$lat,\"lon\":$lon,\"segId\":\"$segmentId\"}"
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log event $eventType", e)
        }
    }

    @Synchronized
    fun stopSession(sessionId: String) {
        if (!isLogging) return
        try {
            val stopJson = "{\"t\":\"STOP\",\"ts\":${System.currentTimeMillis()},\"sid\":\"$sessionId\"}"
            fileWriter?.appendLine(stopJson)
            fileWriter?.flush()
            fileWriter?.close()
            fileWriter = null
            isLogging = false
            Log.i(TAG, "Stopped JSONL session logging: ${sessionFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop JSONL session logging", e)
        }
    }

    fun getActiveLogFilePath(): String? = sessionFile?.absolutePath
}
