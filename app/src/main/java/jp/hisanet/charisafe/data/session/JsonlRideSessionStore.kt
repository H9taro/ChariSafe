package jp.hisanet.charisafe.data.session

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.LocationPoint
import jp.hisanet.charisafe.domain.model.RideMovementState
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
            val json = serializeEvent(event)
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
            val json = serializeEvent(event)
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
    override fun recordStateTick(state: RideMovementState) {
        if (!isLogging) return
        try {
            val event = StateTickEvent(st = state)
            val json = serializeEvent(event)
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
            val json = serializeEvent(event)
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
            val json = serializeEvent(event)
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
            val json = serializeEvent(event)
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
            val json = serializeEvent(event)
            fileWriter?.appendLine(json)
            fileWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record Incident $eventType", e)
        }
    }

    override fun getActiveLogFilePath(): String? = sessionFile?.absolutePath

    private fun serializeEvent(event: SessionEvent): String {
        val typeStr = event.kind.name
        return when (event) {
            is StartEvent -> "{\"t\":\"$typeStr\",\"ts\":${event.ts},\"sid\":\"${event.sid}\",\"ver\":\"${event.ver}\"}"
            is StopEvent -> "{\"t\":\"$typeStr\",\"ts\":${event.ts},\"sid\":\"${event.sid}\"}"
            is StateTickEvent -> "{\"t\":\"$typeStr\",\"ts\":${event.ts},\"st\":\"${event.st.name}\"}"
            is GpsFixEvent -> "{\"t\":\"$typeStr\",\"ts\":${event.ts},\"lat\":${event.point.lat},\"lon\":${event.point.lon},\"ele\":${event.point.ele},\"spd\":${event.spd},\"acc\":${event.accuracy}}"
            is GpsLostEvent -> "{\"t\":\"$typeStr\",\"ts\":${event.ts},\"reason\":\"${event.reason}\"}"
            is GpsRestoredEvent -> "{\"t\":\"$typeStr\",\"ts\":${event.ts},\"lat\":${event.point.lat},\"lon\":${event.point.lon},\"ele\":${event.point.ele},\"spd\":${event.spd},\"acc\":${event.accuracy}}"
            is IncidentEvent -> "{\"t\":\"$typeStr\",\"ts\":${event.ts},\"val\":${event.value},\"th\":${event.threshold},\"lat\":${event.point.lat},\"lon\":${event.point.lon},\"spd\":${event.spd},\"segId\":\"${event.segmentId}\"}"
        }
    }
}
