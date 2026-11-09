package jp.hisanet.charisafe.data.repository

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.data.session.GpsFixEvent
import jp.hisanet.charisafe.data.session.GpsLostEvent
import jp.hisanet.charisafe.data.session.GpsRestoredEvent
import jp.hisanet.charisafe.data.session.IncidentEvent
import jp.hisanet.charisafe.data.session.JsonlSessionParser
import jp.hisanet.charisafe.data.session.StateTickEvent
import jp.hisanet.charisafe.data.session.StopEvent
import jp.hisanet.charisafe.domain.model.EventCountSummary
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.LocationPoint
import jp.hisanet.charisafe.domain.model.RideMovementState
import jp.hisanet.charisafe.domain.model.RideSummary
import jp.hisanet.charisafe.util.FileUtils
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class JsonlSessionRepository(private val context: Context) {
    companion object {
        private const val TAG = "JsonlSessionRepository"
    }

    private val parser = JsonlSessionParser()

    fun calculateRideSummary(file: File, defaultIntervalSec: Long = 1L): RideSummary {
        val events = parser.parseSessionFile(file)
        if (events.isEmpty()) {
            return RideSummary(
                startTimeMs = file.lastModified(),
                endTimeMs = file.lastModified(),
                movingTimeSec = 0L,
                stoppedTimeSec = 0L,
                totalDistanceKm = 0.0,
                averageSpeedKmh = 0f,
                maxSpeedKmh = 0f,
                eventCounts = EventCountSummary(),
                decelerationPerKm = 0f,
                decelerationPerHour = 0f,
                approachPerKm = 0f,
                approachPerHour = 0f,
                collisionPerKm = 0f,
                collisionPerHour = 0f,
                soundPerKm = 0f,
                soundPerHour = 0f
            )
        }

        val startTimeMs = events.first().ts
        val endTimeMs = events.last().ts

        var movingTimeSec = 0L
        var stoppedTimeSec = 0L
        var gpsLostTimeSec = 0L
        var totalDistanceKm = 0.0
        var maxSpeedKmh = 0f

        var approachCount = 0
        var decelerationCount = 0
        var collisionCount = 0
        var soundCount = 0

        var lastTickSecond: Long = -1L
        var lastGpsPoint: LocationPoint? = null
        var lastGpsLostTs: Long = -1L
        var lastGpsFixTs: Long = -1L
        var gpsMovingTimeSec = 0L
        var gpsStoppedTimeSec = 0L

        for (i in events.indices) {
            val event = events[i]
            when (event) {
                is StateTickEvent -> {
                    val currentSecond = event.ts / 1000L
                    if (currentSecond != lastTickSecond) {
                        lastTickSecond = currentSecond
                        val deltaSec = defaultIntervalSec
                        if (event.st == RideMovementState.MOVING) {
                            movingTimeSec += deltaSec
                        } else if (event.st == RideMovementState.STOPPED) {
                            stoppedTimeSec += deltaSec
                        }
                    }
                }
                is GpsFixEvent -> {
                    if (event.spd > maxSpeedKmh) maxSpeedKmh = event.spd
                    if (lastGpsPoint != null) {
                        val dist = lastGpsPoint.distanceTo(event.point)
                        totalDistanceKm += dist
                    }
                    if (lastGpsFixTs > 0L) {
                        val dt = ((event.ts - lastGpsFixTs) / 1000L).coerceIn(1L, 60L)
                        if (event.spd >= 1.0f) {
                            gpsMovingTimeSec += dt
                        } else {
                            gpsStoppedTimeSec += dt
                        }
                    }
                    lastGpsFixTs = event.ts
                    lastGpsPoint = event.point
                }
                is GpsLostEvent -> {
                    lastGpsLostTs = event.ts
                }
                is GpsRestoredEvent -> {
                    if (lastGpsLostTs > 0L) {
                        val lostDuration = ((event.ts - lastGpsLostTs) / 1000L).coerceAtLeast(0L)
                        gpsLostTimeSec += lostDuration
                        lastGpsLostTs = -1L
                    }
                    if (event.spd > maxSpeedKmh) maxSpeedKmh = event.spd
                    if (lastGpsPoint != null) {
                        val dist = lastGpsPoint.distanceTo(event.point)
                        val timeDeltaSec = ((event.ts - (events.getOrNull(i - 1)?.ts ?: event.ts)) / 1000L).coerceAtLeast(1L)
                        val vKmh = (dist / (timeDeltaSec / 3600.0))
                        if (vKmh <= 100.0) {
                            totalDistanceKm += dist
                        }
                    }
                    lastGpsPoint = event.point
                }
                is IncidentEvent -> {
                    when (event.eventType) {
                        EventType.APPROACH -> approachCount++
                        EventType.DECELERATION -> decelerationCount++
                        EventType.COLLISION -> collisionCount++
                        EventType.SOUND -> soundCount++
                    }
                }
                is StopEvent -> {
                    if (lastGpsLostTs > 0L) {
                        gpsLostTimeSec += ((event.ts - lastGpsLostTs) / 1000L).coerceAtLeast(0L)
                        lastGpsLostTs = -1L
                    }
                }
                else -> {}
            }
        }

        val finalMovingSec = if (movingTimeSec > 0L) movingTimeSec else gpsMovingTimeSec
        val finalStoppedSec = if (stoppedTimeSec > 0L) stoppedTimeSec else gpsStoppedTimeSec
        val totalActiveSec = finalMovingSec + finalStoppedSec

        val hours = if (finalMovingSec > 0) finalMovingSec / 3600.0 else (totalActiveSec / 3600.0).coerceAtLeast(1.0 / 3600.0)
        val avgSpeedKmh = if (totalDistanceKm > 0.0 && hours > 0.0) (totalDistanceKm / hours).toFloat() else 0f

        val eventCounts = EventCountSummary(
            approachCount = approachCount,
            decelerationCount = decelerationCount,
            collisionCount = collisionCount,
            soundCount = soundCount
        )

        val distForRate = if (totalDistanceKm > 0.0) totalDistanceKm.toFloat() else 1.0f
        val hoursForRate = if (hours > 0.0) hours.toFloat() else 1.0f

        return RideSummary(
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            movingTimeSec = finalMovingSec,
            stoppedTimeSec = finalStoppedSec,
            totalDistanceKm = totalDistanceKm,
            averageSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            eventCounts = eventCounts,
            decelerationPerKm = decelerationCount / distForRate,
            decelerationPerHour = decelerationCount / hoursForRate,
            approachPerKm = approachCount / distForRate,
            approachPerHour = approachCount / hoursForRate,
            collisionPerKm = collisionCount / distForRate,
            collisionPerHour = collisionCount / hoursForRate,
            soundPerKm = soundCount / distForRate,
            soundPerHour = soundCount / hoursForRate
        )
    }

    fun compareWithGpxSummary(jsonlFile: File, gpxSummary: RideSummary) {
        try {
            val jsonlSummary = calculateRideSummary(jsonlFile)
            val distDelta = jsonlSummary.totalDistanceKm - gpxSummary.totalDistanceKm
            val movingDelta = jsonlSummary.movingTimeSec - gpxSummary.movingTimeSec
            val stoppedDelta = jsonlSummary.stoppedTimeSec - gpxSummary.stoppedTimeSec
            val countMatch = (jsonlSummary.eventCounts.approachCount == gpxSummary.eventCounts.approachCount &&
                    jsonlSummary.eventCounts.decelerationCount == gpxSummary.eventCounts.decelerationCount &&
                    jsonlSummary.eventCounts.collisionCount == gpxSummary.eventCounts.collisionCount &&
                    jsonlSummary.eventCounts.soundCount == gpxSummary.eventCounts.soundCount)

            val matchStatus = if (abs(distDelta) < 0.1 && movingDelta == 0L && stoppedDelta == 0L && countMatch) "MATCH" else "MISMATCH"

            Log.i(TAG, "🔍 [JsonlComparison] file=${jsonlFile.name} status=$matchStatus | Dist(GPX=${gpxSummary.totalDistanceKm}, JSONL=${jsonlSummary.totalDistanceKm}) | Moving(GPX=${gpxSummary.movingTimeSec}, JSONL=${jsonlSummary.movingTimeSec}) | CountMatch=$countMatch")

            persistComparisonResult(jsonlFile.name, gpxSummary, jsonlSummary, matchStatus)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compare JSONL summary with GPX summary", e)
        }
    }

    private fun persistComparisonResult(fileName: String, gpx: RideSummary, jsonl: RideSummary, status: String) {
        try {
            val debugDir = FileUtils.getDebugDir(context)
            if (!debugDir.exists()) debugDir.mkdirs()
            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val csvFile = File(debugDir, "jsonl_comparison_${dateStr}.csv")
            val isNew = !csvFile.exists()

            FileWriter(csvFile, true).use { writer ->
                if (isNew) {
                    writer.appendLine("timestamp,filename,gpx_dist_km,jsonl_dist_km,dist_delta,gpx_moving_sec,jsonl_moving_sec,gpx_stopped_sec,jsonl_stopped_sec,status")
                }
                val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
                val distDelta = jsonl.totalDistanceKm - gpx.totalDistanceKm
                writer.appendLine("$ts,$fileName,${gpx.totalDistanceKm},${jsonl.totalDistanceKm},$distDelta,${gpx.movingTimeSec},${jsonl.movingTimeSec},${gpx.stoppedTimeSec},${jsonl.stoppedTimeSec},$status")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist comparison result to CSV", e)
        }
    }
}
