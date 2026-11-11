package jp.hisanet.charisafe.domain.ride

import jp.hisanet.charisafe.domain.model.EventCountSummary
import jp.hisanet.charisafe.domain.model.GpxPoint
import jp.hisanet.charisafe.domain.model.RideSummary

class LiveRideSummaryService {
    fun calculateLiveSummary(
        recordedPoints: List<GpxPoint>,
        detections: List<jp.hisanet.charisafe.domain.model.EventDetectionLog>,
        sessionStartMs: Long,
        rideStartMs: Long
    ): RideSummary {
        val activeStart = if (sessionStartMs > 0L) sessionStartMs else (rideStartMs.takeIf { it > 0L } ?: System.currentTimeMillis())
        val totalActiveSec = if (sessionStartMs > 0L || rideStartMs > 0L) ((System.currentTimeMillis() - activeStart) / 1000L).coerceAtLeast(0L) else 0L

        val totalCounts = EventCountSummary(
            approachCount = detections.count { it.eventType == jp.hisanet.charisafe.domain.model.EventType.APPROACH },
            decelerationCount = detections.count { it.eventType == jp.hisanet.charisafe.domain.model.EventType.DECELERATION },
            collisionCount = detections.count { it.eventType == jp.hisanet.charisafe.domain.model.EventType.COLLISION },
            soundCount = detections.count { it.eventType == jp.hisanet.charisafe.domain.model.EventType.SOUND }
        )

        val summary = RideMetricsCalculator.calculateSummaryFromPoints(
            points = recordedPoints,
            counts = totalCounts,
            summaryId = "live_ride_summary",
            gpxFileName = "live_active"
        )
        // Adjust start time if needed
        return summary.copy(startTimeMs = activeStart)
    }
}
