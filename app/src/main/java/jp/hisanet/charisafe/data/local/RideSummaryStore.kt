package jp.hisanet.charisafe.data.local

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.domain.model.EventCountSummary
import jp.hisanet.charisafe.domain.model.RideSummary
import jp.hisanet.charisafe.util.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.FileWriter

object RideSummaryStore {
    private const val TAG = "RideSummaryStore"
    private const val FILE_NAME = "ride_summaries.json"

    private fun getFile(context: Context): File {
        val dir = FileUtils.getLogsDir(context)
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun saveSummaries(context: Context, summaries: List<RideSummary>) {
        try {
            val jsonArray = JSONArray()
            summaries.forEach { sum ->
                val obj = JSONObject().apply {
                    put("startTimeMs", sum.startTimeMs)
                    put("endTimeMs", sum.endTimeMs)
                    put("movingTimeSec", sum.movingTimeSec)
                    put("stoppedTimeSec", sum.stoppedTimeSec)
                    put("totalDistanceKm", sum.totalDistanceKm)
                    put("averageSpeedKmh", sum.averageSpeedKmh.toDouble())
                    put("maxSpeedKmh", sum.maxSpeedKmh.toDouble())
                    
                    val countsObj = JSONObject().apply {
                        put("decelerationCount", sum.eventCounts.decelerationCount)
                        put("approachCount", sum.eventCounts.approachCount)
                        put("collisionCount", sum.eventCounts.collisionCount)
                        put("soundCount", sum.eventCounts.soundCount)
                    }
                    put("eventCounts", countsObj)

                    put("decelerationPerKm", sum.decelerationPerKm.toDouble())
                    put("decelerationPerHour", sum.decelerationPerHour.toDouble())
                    put("approachPerKm", sum.approachPerKm.toDouble())
                    put("approachPerHour", sum.approachPerHour.toDouble())
                    put("collisionPerKm", sum.collisionPerKm.toDouble())
                    put("collisionPerHour", sum.collisionPerHour.toDouble())
                    put("soundPerKm", sum.soundPerKm.toDouble())
                    put("soundPerHour", sum.soundPerHour.toDouble())
                }
                jsonArray.put(obj)
            }

            val file = getFile(context)
            FileWriter(file, false).use { writer ->
                writer.write(jsonArray.toString())
            }
            Log.i(TAG, "Successfully saved ${summaries.size} ride summaries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ride summaries", e)
        }
    }

    @Synchronized
    fun loadSummaries(context: Context): List<RideSummary> {
        val file = getFile(context)
        if (!file.exists() || file.length() == 0L) return emptyList()

        try {
            val sb = StringBuilder()
            FileReader(file).use { reader ->
                reader.readLines().forEach { sb.append(it) }
            }

            val jsonArray = JSONArray(sb.toString())
            val list = mutableListOf<RideSummary>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val countsObj = obj.optJSONObject("eventCounts")
                val counts = EventCountSummary(
                    decelerationCount = countsObj?.optInt("decelerationCount", 0) ?: 0,
                    approachCount = countsObj?.optInt("approachCount", 0) ?: 0,
                    collisionCount = countsObj?.optInt("collisionCount", 0) ?: 0,
                    soundCount = countsObj?.optInt("soundCount", 0) ?: 0
                )

                val summary = RideSummary(
                    startTimeMs = obj.optLong("startTimeMs", 0L),
                    endTimeMs = obj.optLong("endTimeMs", 0L),
                    movingTimeSec = obj.optLong("movingTimeSec", 0L),
                    stoppedTimeSec = obj.optLong("stoppedTimeSec", 0L),
                    totalDistanceKm = obj.optDouble("totalDistanceKm", 0.0),
                    averageSpeedKmh = obj.optDouble("averageSpeedKmh", 0.0).toFloat(),
                    maxSpeedKmh = obj.optDouble("maxSpeedKmh", 0.0).toFloat(),
                    eventCounts = counts,
                    decelerationPerKm = obj.optDouble("decelerationPerKm", 0.0).toFloat(),
                    decelerationPerHour = obj.optDouble("decelerationPerHour", 0.0).toFloat(),
                    approachPerKm = obj.optDouble("approachPerKm", 0.0).toFloat(),
                    approachPerHour = obj.optDouble("approachPerHour", 0.0).toFloat(),
                    collisionPerKm = obj.optDouble("collisionPerKm", 0.0).toFloat(),
                    collisionPerHour = obj.optDouble("collisionPerHour", 0.0).toFloat(),
                    soundPerKm = obj.optDouble("soundPerKm", 0.0).toFloat(),
                    soundPerHour = obj.optDouble("soundPerHour", 0.0).toFloat()
                )
                list.add(summary)
            }
            return list.sortedByDescending { it.startTimeMs }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ride summaries", e)
            return emptyList()
        }
    }
}
