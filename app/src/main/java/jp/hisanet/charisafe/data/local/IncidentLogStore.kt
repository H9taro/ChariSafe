package jp.hisanet.charisafe.data.local

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.data.location.LocationTracker
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.IncidentLog
import jp.hisanet.charisafe.domain.model.IncidentStatus
import jp.hisanet.charisafe.util.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.FileWriter

object IncidentLogStore {
    private const val TAG = "IncidentLogStore"
    private const val FILE_NAME = "incident_logs.json"

    private fun getFile(context: Context): File {
        val dir = FileUtils.getLogsDir(context)
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun saveIncidents(context: Context, incidents: List<IncidentLog>) {
        try {
            val jsonArray = JSONArray()
            incidents.forEach { inc ->
                val obj = JSONObject().apply {
                    put("id", inc.id)
                    put("timestampMs", inc.timestampMs)
                    put("triggerEventType", inc.triggerEventType.name)
                    put("triggerDetectionLogId", inc.triggerDetectionLogId)
                    val arr = JSONArray()
                    inc.relatedDetectionIds.forEach { arr.put(it) }
                    put("relatedDetectionIds", arr)
                    put("highestSeverityType", inc.highestSeverityType.name)
                    put("latitude", inc.latitude)
                    put("longitude", inc.longitude)
                    put("videoFilePath", inc.videoFilePath)
                    put("status", inc.status.name)
                    put("failureReason", inc.failureReason ?: "")
                }
                jsonArray.put(obj)
            }

            val file = getFile(context)
            FileWriter(file, false).use { writer ->
                writer.write(jsonArray.toString())
            }
            Log.i(TAG, "Successfully saved ${incidents.size} incident logs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save incident logs", e)
        }
    }

    @Synchronized
    fun loadIncidents(context: Context): List<IncidentLog> {
        val file = getFile(context)
        if (!file.exists() || file.length() == 0L) return emptyList()

        try {
            val sb = StringBuilder()
            FileReader(file).use { reader ->
                reader.readLines().forEach { sb.append(it) }
            }

            val jsonArray = JSONArray(sb.toString())
            val list = mutableListOf<IncidentLog>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val typeStr = obj.optString("triggerEventType", EventType.APPROACH.name)
                val triggerType = try { EventType.valueOf(typeStr) } catch (e: Exception) { EventType.APPROACH }

                val severityStr = obj.optString("highestSeverityType", triggerType.name)
                val severityType = try { EventType.valueOf(severityStr) } catch (e: Exception) { triggerType }

                val statusStr = obj.optString("status", IncidentStatus.SUCCESS.name)
                val status = try { IncidentStatus.valueOf(statusStr) } catch (e: Exception) { IncidentStatus.SUCCESS }

                val relArr = obj.optJSONArray("relatedDetectionIds")
                val relIds = mutableListOf<String>()
                if (relArr != null) {
                    for (j in 0 until relArr.length()) {
                        relIds.add(relArr.getString(j))
                    }
                }

                val inc = IncidentLog(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                    triggerEventType = triggerType,
                    triggerDetectionLogId = obj.optString("triggerDetectionLogId", ""),
                    relatedDetectionIds = relIds,
                    highestSeverityType = severityType,
                    latitude = obj.optDouble("latitude", LocationTracker.lastValidLatitude),
                    longitude = obj.optDouble("longitude", LocationTracker.lastValidLongitude),
                    videoFilePath = obj.optString("videoFilePath", ""),
                    status = status,
                    failureReason = obj.optString("failureReason", "").takeIf { it.isNotEmpty() }
                )
                list.add(inc)
            }
            return list.sortedByDescending { it.timestampMs }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load incident logs", e)
            return emptyList()
        }
    }
}
