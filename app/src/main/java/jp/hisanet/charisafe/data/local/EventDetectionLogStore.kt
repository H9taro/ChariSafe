package jp.hisanet.charisafe.data.local

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.data.location.LocationTracker
import jp.hisanet.charisafe.domain.model.EventDetectionLog
import jp.hisanet.charisafe.domain.model.EventSensitivity
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.util.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.FileWriter

object EventDetectionLogStore {
    private const val TAG = "EventDetectionLogStore"
    private const val FILE_NAME = "event_detection_logs.json"

    private fun getFile(context: Context): File {
        val dir = FileUtils.getLogsDir(context)
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun saveLogs(context: Context, logs: List<EventDetectionLog>) {
        try {
            val jsonArray = JSONArray()
            logs.forEach { log ->
                val obj = JSONObject().apply {
                    put("id", log.id)
                    put("timestampMs", log.timestampMs)
                    put("eventType", log.eventType.name)
                    put("speedKmh", log.speedKmh.toDouble())
                    put("latitude", log.latitude)
                    put("longitude", log.longitude)
                    put("sensitivityValue", log.sensitivityValue.toDouble())
                    put("sensitivityLabel", log.sensitivityLabel)
                    put("incidentId", log.incidentId ?: "")
                }
                jsonArray.put(obj)
            }

            val file = getFile(context)
            FileWriter(file, false).use { writer ->
                writer.write(jsonArray.toString())
            }
            Log.i(TAG, "Successfully saved ${logs.size} event detection logs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save event detection logs", e)
        }
    }

    @Synchronized
    fun loadLogs(context: Context): List<EventDetectionLog> {
        val file = getFile(context)
        if (!file.exists() || file.length() == 0L) return emptyList()

        try {
            val sb = StringBuilder()
            FileReader(file).use { reader ->
                reader.readLines().forEach { sb.append(it) }
            }

            val jsonArray = JSONArray(sb.toString())
            val list = mutableListOf<EventDetectionLog>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val typeStr = obj.optString("eventType", EventType.APPROACH.name)
                val eventType = try { EventType.valueOf(typeStr) } catch (e: Exception) { EventType.APPROACH }

                val log = EventDetectionLog(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                    eventType = eventType,
                    speedKmh = obj.optDouble("speedKmh", 0.0).toFloat(),
                    latitude = obj.optDouble("latitude", LocationTracker.lastValidLatitude),
                    longitude = obj.optDouble("longitude", LocationTracker.lastValidLongitude),
                    sensitivityValue = obj.optDouble("sensitivityValue", 0.7).toFloat(),
                    sensitivityLabel = obj.optString("sensitivityLabel", ""),
                    sensitivitySnapshot = EventSensitivity(),
                    incidentId = obj.optString("incidentId", "").takeIf { it.isNotEmpty() }
                )
                list.add(log)
            }
            return list.sortedByDescending { it.timestampMs }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load event detection logs", e)
            return emptyList()
        }
    }
}
