package jp.hisanet.charisafe.data.debug

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.domain.model.EventTraceEntry
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.SegmentProtectionTraceEntry
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 系統 A: イベント判定トレースロガー
 */
class TraceLogger(private val context: Context) {

    companion object {
        private const val TAG = "TraceLogger"
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    }

    private fun getDebugDir(): File {
        val dir = context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun logEventTrace(entry: EventTraceEntry) {
        try {
            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(entry.timestampMs))
            val prefix = when (entry.eventType) {
                EventType.APPROACH -> "approach_trace"
                EventType.DECELERATION -> "deceleration_trace"
                EventType.COLLISION -> "collision_trace"
                EventType.SOUND -> "audio_trace"
            }
            val fileName = "${prefix}_$dateStr.csv"
            val file = File(getDebugDir(), fileName)

            val isNew = !file.exists()
            FileWriter(file, true).use { writer ->
                if (isNew) {
                    val header = when (entry.eventType) {
                        EventType.APPROACH -> "timestamp,segmentName,approachScore,threshold,isTriggered"
                        EventType.DECELERATION -> "timestamp,segmentName,speedRawKmh,speedEmaKmh,decelerationMs2,thresholdMs2,consecutiveCount,isTriggered"
                        EventType.COLLISION -> "timestamp,segmentName,gValue,thresholdG,isTriggered"
                        EventType.SOUND -> "timestamp,segmentName,amplitude,thresholdAmplitude,isMuted,isTriggered"
                    }
                    writer.append("$header\n")
                }

                val isoTime = dateFormat.format(Date(entry.timestampMs))
                val row = when (entry.eventType) {
                    EventType.APPROACH -> "$isoTime,${entry.segmentName},${entry.currentValue},${entry.thresholdValue},${entry.isTriggered}"
                    EventType.DECELERATION -> "$isoTime,${entry.segmentName},${entry.rawValue},${entry.smoothedValue},${entry.currentValue},${entry.thresholdValue},${entry.consecutiveCount},${entry.isTriggered}"
                    EventType.COLLISION -> "$isoTime,${entry.segmentName},${entry.currentValue},${entry.thresholdValue},${entry.isTriggered}"
                    EventType.SOUND -> "$isoTime,${entry.segmentName},${entry.currentValue},${entry.thresholdValue},${entry.isMuted},${entry.isTriggered}"
                }
                writer.append("$row\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event trace entry", e)
        }
    }

    @Synchronized
    fun logProtectionTrace(entry: SegmentProtectionTraceEntry) {
        try {
            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(entry.timestampMs))
            val fileName = "segment_protection_trace_$dateStr.csv"
            val file = File(getDebugDir(), fileName)

            val isNew = !file.exists()
            FileWriter(file, true).use { writer ->
                if (isNew) {
                    writer.append("timestamp,segmentName,isProtected,pendingProtection,triggerEvent,reason,notificationSent,operationSucceeded,failureReason,associatedEventId\n")
                }
                val isoTime = dateFormat.format(Date(entry.timestampMs))
                val failStr = entry.failureReason?.name ?: ""
                val row = "$isoTime,${entry.segmentName},${entry.isProtected},${entry.pendingProtection},${entry.triggerEventType},${entry.protectionReason.name},${entry.notificationSent},${entry.operationSucceeded},$failStr,${entry.associatedEventId ?: ""}"
                writer.append("$row\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write protection trace entry", e)
        }
    }
}
