package jp.hisanet.charisafe.domain.ride

import android.content.Context
import jp.hisanet.charisafe.data.repository.JsonlSessionRepository
import jp.hisanet.charisafe.domain.model.EventCountSummary
import jp.hisanet.charisafe.domain.model.RideSummary
import jp.hisanet.charisafe.util.FileUtils
import java.io.File

class JsonlRideSummaryService(private val context: Context) {
    private val jsonlRepo = JsonlSessionRepository(context)

    fun calculateCumulativeSummary(): RideSummary? {
        val logsDir = FileUtils.getLogsDir(context)
        val jsonlFiles = if (logsDir.exists()) {
            logsDir.listFiles { _, name -> name.endsWith(".jsonl") }?.sortedBy { it.lastModified() } ?: emptyList()
        } else emptyList()

        if (jsonlFiles.isEmpty()) return null

        val sessionSummaries = jsonlFiles.map { jsonlRepo.calculateRideSummary(it) }
        val validSummaries = sessionSummaries.filter {
            it.startTimeMs > 0L && (it.totalDistanceKm > 0.0 || it.movingTimeSec > 0L || it.eventCounts.approachCount > 0 || it.eventCounts.decelerationCount > 0 || it.eventCounts.collisionCount > 0 || it.eventCounts.soundCount > 0)
        }

        if (validSummaries.isEmpty()) return null

        val startTimeMs = validSummaries.minOf { it.startTimeMs }
        val endTimeMs = validSummaries.maxOf { it.endTimeMs }
        val movingSec = validSummaries.sumOf { it.movingTimeSec }
        val stoppedSec = validSummaries.sumOf { it.stoppedTimeSec }
        val totalDistKm = validSummaries.sumOf { it.totalDistanceKm }
        val maxSpd = validSummaries.maxOfOrNull { it.maxSpeedKmh } ?: 0f

        val approachCount = validSummaries.sumOf { it.eventCounts.approachCount }
        val decelerationCount = validSummaries.sumOf { it.eventCounts.decelerationCount }
        val collisionCount = validSummaries.sumOf { it.eventCounts.collisionCount }
        val soundCount = validSummaries.sumOf { it.eventCounts.soundCount }

        val counts = EventCountSummary(
            approachCount = approachCount,
            decelerationCount = decelerationCount,
            collisionCount = collisionCount,
            soundCount = soundCount
        )

        val hours = if (movingSec > 0L) movingSec / 3600.0 else ((movingSec + stoppedSec) / 3600.0).coerceAtLeast(1.0 / 3600.0)
        val avgSpd = if (totalDistKm > 0.0 && movingSec > 0L) (totalDistKm / (movingSec / 3600.0)).toFloat() else if (totalDistKm > 0.0 && hours > 0.0) (totalDistKm / hours).toFloat() else 0f

        val distForRate = if (totalDistKm > 0.0) totalDistKm.toFloat() else 1.0f
        val hoursForRate = if (hours > 0.0) hours.toFloat() else 1.0f

        return RideSummary(
            id = "cumulative_ride_summary",
            gpxFileName = "all_rides_accumulated",
            isCompleted = true,
            lastAggregatedTimestampMs = endTimeMs,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            movingTimeSec = movingSec,
            stoppedTimeSec = stoppedSec,
            totalDistanceKm = totalDistKm,
            averageSpeedKmh = avgSpd,
            maxSpeedKmh = maxSpd,
            eventCounts = counts,
            decelerationPerKm = counts.decelerationCount.toFloat() / distForRate,
            decelerationPerHour = counts.decelerationCount.toFloat() / hoursForRate,
            approachPerKm = counts.approachCount.toFloat() / distForRate,
            approachPerHour = counts.approachCount.toFloat() / hoursForRate,
            collisionPerKm = counts.collisionCount.toFloat() / distForRate,
            collisionPerHour = counts.collisionCount.toFloat() / hoursForRate,
            soundPerKm = counts.soundCount.toFloat() / distForRate,
            soundPerHour = counts.soundCount.toFloat() / hoursForRate
        )
    }

    fun getJsonlFiles(): List<File> {
        val logsDir = FileUtils.getLogsDir(context)
        return if (logsDir.exists()) {
            logsDir.listFiles { _, name -> name.endsWith(".jsonl") }?.sortedBy { it.lastModified() } ?: emptyList()
        } else emptyList()
    }
}
