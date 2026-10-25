package jp.hisanet.charisafe.data.local

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.data.location.LocationTracker
import jp.hisanet.charisafe.domain.model.EventLog
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.util.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.FileWriter

object EventLogStore {

    private const val TAG = "EventLogStore"
    private const val FILE_NAME = "event_logs.json"

    private fun getFile(context: Context): File {
        val dir = FileUtils.getLogsDir(context)
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun saveEvents(context: Context, logs: List<EventLog>) {
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
                    put("decelerationSensitivity", log.decelerationSensitivity.toDouble())
                    put("approachSensitivity", log.approachSensitivity.toDouble())
                    put("collisionSensitivity", log.collisionSensitivity.toDouble())
                    put("soundSensitivity", log.soundSensitivity.toDouble())
                    put("videoFilePath", log.videoFilePath ?: "")
                }
                jsonArray.put(obj)
            }

            val file = getFile(context)
            FileWriter(file, false).use { writer ->
                writer.write(jsonArray.toString())
            }
            Log.i(TAG, "Successfully saved ${logs.size} event logs to JSON: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save event logs to JSON", e)
        }
    }

    @Synchronized
    fun loadEvents(context: Context): List<EventLog> {
        val file = getFile(context)
        if (!file.exists() || file.length() == 0L) return emptyList()

        try {
            val sb = StringBuilder()
            FileReader(file).use { reader ->
                reader.readLines().forEach { sb.append(it) }
            }

            val jsonArray = JSONArray(sb.toString())
            val list = mutableListOf<EventLog>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val typeStr = obj.optString("eventType", EventType.APPROACH.name)
                val eventType = try { EventType.valueOf(typeStr) } catch (e: Exception) { EventType.APPROACH }

                val log = EventLog(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                    eventType = eventType,
                    speedKmh = obj.optDouble("speedKmh", 0.0).toFloat(),
                    latitude = obj.optDouble("latitude", LocationTracker.lastValidLatitude),
                    longitude = obj.optDouble("longitude", LocationTracker.lastValidLongitude),
                    decelerationSensitivity = obj.optDouble("decelerationSensitivity", 1.5).toFloat(),
                    approachSensitivity = obj.optDouble("approachSensitivity", 0.7).toFloat(),
                    collisionSensitivity = obj.optDouble("collisionSensitivity", 3.0).toFloat(),
                    soundSensitivity = obj.optDouble("soundSensitivity", 85.0).toFloat(),
                    videoFilePath = obj.optString("videoFilePath", "").takeIf { it.isNotEmpty() }
                )
                list.add(log)
            }
            Log.i(TAG, "Successfully loaded ${list.size} event logs from JSON")
            return list.sortedByDescending { it.timestampMs }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load event logs from JSON", e)
            return emptyList()
        }
    }
}
