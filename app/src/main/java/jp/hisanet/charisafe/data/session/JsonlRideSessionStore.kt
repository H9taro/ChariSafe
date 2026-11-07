package jp.hisanet.charisafe.data.session

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.LocationPoint
import jp.hisanet.charisafe.domain.repository.RideSessionStore
import jp.hisanet.charisafe.util.FileUtils
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JsonlRideSessionStore(private val context: Context) : RideSessionStore {
    companion object {
        private const val TAG = "JsonlRideSessionStore"
    }

    private var sessionFile: File? = null
    private var fileWriter: FileWriter? = null
    private var isLogging = false

    @Synchronized
    override fun startSession(sessionId: String) {
        if (isLogging) return
        try {
            val logsDir = FileUtils.getLogsDir(context)
            if (!logsDir.exists()) logsDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(logsDir, "session_${timestamp}.jsonl")
            sessionFile = file
            fileWriter = FileWriter(file, true)

            val event = StartEvent(sid = sessionId)
            val json = serializeStart(event)
            fileWriter?.appendLine(json)
            fileWriter?.flush()
            isLogging = true
            Log.i(TAG, "Started JSONL session store logging to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start JSONL session store logging", e)
        }
    }

    @Synchronized
    override fun stopSession(sessionId: String) {
        if (!isLogging) return
        try {
            val event = StopEvent(sid = sessionId)
            val json = serializeStop(event)
            fileWriter?.appendLine(json)
            fileWriter?.flush()
            fileWriter?.close()
            fileWriter = null
            isLogging = false
            Log.i(TAG, "Stopped JSONL session store logging: ${sessionFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop JSONL session store logging", e)
        }
    }

    @Synchronized
    override fun recordStateTick(state: String) {
        if (!isLogging) return
        try {
            val event = StateTickEvent(st = state)
            val json = serializeStateTick(event)
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record STATE_TICK", e)
        }
    }

    @Synchronized
    override fun recordGpsFix(point: LocationPoint, spd: Float, accuracy: Float) {
        if (!isLogging) return
        try {
            val event = GpsFixEvent(point = point, spd = spd, accuracy = accuracy)
            val json = serializeGpsFix(event)
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record GPS_FIX", e)
        }
    }

    @Synchronized
    override fun recordGpsLost(reason: String) {
        if (!isLogging) return
        try {
            val event = GpsLostEvent(reason = reason)
            val json = serializeGpsLost(event)
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record GPS_LOST", e)
        }
    }

    @Synchronized
    override fun recordGpsRestored(point: LocationPoint, spd: Float, accuracy: Float) {
        if (!isLogging) return
        try {
            val event = GpsRestoredEvent(point = point, spd = spd, accuracy = accuracy)
            val json = serializeGpsRestored(event)
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record GPS_RESTORED", e)
        }
    }

    @Synchronized
    override fun recordIncident(
        eventType: EventType,
        value: Float,
        threshold: Float,
        point: LocationPoint,
        segmentId: String
    ) {
        if (!isLogging) return
        try {
            val event = IncidentEvent(eventType = eventType, value = value, threshold = threshold, point = point, segmentId = segmentId)
            val json = serializeIncident(event)
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record Incident $eventType", e)
        }
    }

    override fun getActiveLogFilePath(): String? = sessionFile?.absolutePath

    private fun serializeStart(e: StartEvent): String {
        return "{\"t\":\"START\",\"ts\":${e.ts},\"sid\":\"${e.sid}\",\"ver\":\"${e.ver}\"}"
    }

    private fun serializeStop(e: StopEvent): String {
        return "{\"t\":\"STOP\",\"ts\":${e.ts},\"sid\":\"${e.sid}\"}"
    }

    private fun serializeStateTick(e: StateTickEvent): String {
        return "{\"t\":\"STATE_TICK\",\"ts\":${e.ts},\"st\":\"${e.st}\"}"
    }

    private fun serializeGpsFix(e: GpsFixEvent): String {
        return "{\"t\":\"GPS_FIX\",\"ts\":${e.ts},\"lat\":${e.point.lat},\"lon\":${e.point.lon},\"ele\":${e.point.ele},\"spd\":${e.spd},\"acc\":${e.accuracy}}"
    }

    private fun serializeGpsLost(e: GpsLostEvent): String {
        return "{\"t\":\"GPS_LOST\",\"ts\":${e.ts},\"reason\":\"${e.reason}\"}"
    }

    private fun serializeGpsRestored(e: GpsRestoredEvent): String {
        return "{\"t\":\"GPS_RESTORED\",\"ts\":${e.ts},\"lat\":${e.point.lat},\"lon\":${e.point.lon},\"ele\":${e.point.ele},\"spd\":${e.spd},\"acc\":${e.accuracy}}"
    }

    private fun serializeIncident(e: IncidentEvent): String {
        return "{\"t\":\"${e.eventType.name}\",\"ts\":${e.ts},\"val\":${e.value},\"th\":${e.threshold},\"lat\":${e.point.lat},\"lon\":${e.point.lon},\"spd\":${e.spd},\"segId\":\"${e.segmentId}\"}"
    }
}
