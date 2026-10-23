package jp.hisanet.charisafe.domain.model

import java.util.UUID

enum class EventType(val displayName: String, val emoji: String) {
    DECELERATION("急減速", "⚠"),
    APPROACH("接近検知", "🚗"),
    COLLISION("衝突・落下", "💥"),
    SOUND("大音量検知", "🔊")
}

data class EventLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val speedKmh: Float,
    val latitude: Double,
    val longitude: Double,
    val decelerationSensitivity: Float,
    val approachSensitivity: Float,
    val collisionSensitivity: Float,
    val soundSensitivity: Float,
    val videoFilePath: String? = null
)
data class EventCountSummary(
    val decelerationCount: Int = 0,
    val approachCount: Int = 0,
    val collisionCount: Int = 0,
    val soundCount: Int = 0
) {
    val totalCount: Int
        get() = decelerationCount + approachCount + collisionCount + soundCount
}

data class EventSensitivity(
    val decelerationSensitivity: Float = 15.0f, // km/h threshold
    val approachSensitivity: Float = 0.7f,     // 70%
    val collisionSensitivity: Float = 3.0f,    // 3.0G
    val soundSensitivity: Float = 25000f       // Amplitude
)

data class RideSummary(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val movingTimeSec: Long,
    val stoppedTimeSec: Long,
    val totalDistanceKm: Double,
    val averageSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val eventCounts: EventCountSummary,
    val approachPerKm: Float,
    val approachPerHour: Float,
    val decelerationPerKm: Float,
    val decelerationPerHour: Float
)
