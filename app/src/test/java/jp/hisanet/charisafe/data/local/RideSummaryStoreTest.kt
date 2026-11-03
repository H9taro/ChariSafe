package jp.hisanet.charisafe.data.local

import jp.hisanet.charisafe.domain.model.EventCountSummary
import jp.hisanet.charisafe.domain.model.RideSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideSummaryStoreTest {

    @Test
    fun testRideSummaryModelFields() {
        val summary = RideSummary(
            id = "test-id-123",
            gpxFileName = "ride_20261103_120000.gpx",
            isCompleted = true,
            lastAggregatedTimestampMs = 1793680000000L,
            startTimeMs = 1793678352000L,
            endTimeMs = 1793678985000L,
            movingTimeSec = 547L,
            stoppedTimeSec = 86L,
            totalDistanceKm = 1.121768,
            averageSpeedKmh = 11.58f,
            maxSpeedKmh = 76.0f,
            eventCounts = EventCountSummary(decelerationCount = 5, approachCount = 1, collisionCount = 2, soundCount = 1),
            decelerationPerKm = 4.4f,
            decelerationPerHour = 10.0f,
            approachPerKm = 1.0f,
            approachPerHour = 2.0f,
            collisionPerKm = 2.0f,
            collisionPerHour = 3.0f,
            soundPerKm = 1.0f,
            soundPerHour = 1.0f
        )

        assertEquals("ride_20261103_120000.gpx", summary.gpxFileName)
        assertTrue(summary.isCompleted)
        assertEquals(1793680000000L, summary.lastAggregatedTimestampMs)
        assertEquals(1.121768, summary.totalDistanceKm, 0.0001)
        assertEquals(547L, summary.movingTimeSec)
    }
}
