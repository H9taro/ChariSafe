package jp.hisanet.charisafe.domain.ride

import jp.hisanet.charisafe.domain.model.EventCountSummary
import jp.hisanet.charisafe.domain.model.GpxPoint
import jp.hisanet.charisafe.domain.model.RideSummary
import kotlin.math.cos
import kotlin.math.sin

class RideMetricsCalculator {
    companion object {
        fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
            return r * c
        }

        fun calculateSummaryFromPoints(
            points: List<GpxPoint>,
            counts: EventCountSummary,
            summaryId: String = "gpx_summary",
            gpxFileName: String = ""
        ): RideSummary {
            if (points.isEmpty()) {
                return RideSummary(
                    id = summaryId,
                    gpxFileName = gpxFileName,
                    isCompleted = true,
                    startTimeMs = 0L,
                    endTimeMs = 0L,
                    movingTimeSec = 0L,
                    stoppedTimeSec = 0L,
                    totalDistanceKm = 0.0,
                    averageSpeedKmh = 0f,
                    maxSpeedKmh = 0f,
                    eventCounts = EventCountSummary(),
                    decelerationPerKm = 0f, decelerationPerHour = 0f,
                    approachPerKm = 0f, approachPerHour = 0f,
                    collisionPerKm = 0f, collisionPerHour = 0f,
                    soundPerKm = 0f, soundPerHour = 0f
                )
            }

            val sortedPoints = points.sortedBy { it.timestampMs }
            val startTimeMs = sortedPoints.first().timestampMs
            val endTimeMs = sortedPoints.last().timestampMs

            var totalDistKm = 0.0
            var maxSpd = 0f
            var movingSec = 0L
            var stoppedSec = 0L

            for (i in 1 until sortedPoints.size) {
                val p1 = sortedPoints[i - 1]
                val p2 = sortedPoints[i]
                val dist = calculateHaversineDistance(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
                totalDistKm += dist
                if (p2.speedKmh > maxSpd) maxSpd = p2.speedKmh

                val dt = (p2.timestampMs - p1.timestampMs) / 1000L
                if (dt in 1..60) {
                    if (p2.speedKmh >= 1.0f) {
                        movingSec += dt
                    } else {
                        stoppedSec += dt
                    }
                }
            }

            val totalDurationSec = if (startTimeMs > 0) (endTimeMs - startTimeMs) / 1000L else 0L
            if (movingSec == 0L && stoppedSec == 0L && totalDurationSec > 0L) {
                movingSec = totalActiveSecFallback(totalDurationSec)
            }

            // Fixed average speed formula: Total Distance / Moving Time
            val avgSpd = if (totalDistKm > 0.0 && movingSec > 0L) (totalDistKm / (movingSec / 3600.0)).toFloat() else 0f
            val hours = if (movingSec > 0L) movingSec / 3600.0 else (totalDurationSec / 3600.0).coerceAtLeast(1.0 / 3600.0)

            val distForRate = if (totalDistKm > 0.0) totalDistKm.toFloat() else 1.0f
            val hoursForRate = if (hours > 0.0) hours.toFloat() else 1.0f

            return RideSummary(
                id = summaryId,
                gpxFileName = gpxFileName,
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

        private fun totalActiveSecFallback(durationSec: Long): Long = durationSec
    }
}
