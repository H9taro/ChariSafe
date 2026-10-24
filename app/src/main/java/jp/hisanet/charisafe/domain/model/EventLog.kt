package jp.hisanet.charisafe.domain.model

import java.util.UUID

enum class EventType(val displayName: String, val emoji: String, val orderIndex: Int) {
    DECELERATION("急減速", "⚠", 0),
    APPROACH("接近検知", "🚗", 1),
    COLLISION("衝突・落下", "💥", 2),
    SOUND("大音量検知", "🔊", 3)
}

data class EventLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val speedKmh: Float,
    val latitude: Double,
    val longitude: Double,
    val decelerationSensitivity: Float, // 秒数 (例: 1.5秒)
    val approachSensitivity: Float,     // 接近検知 (例: 70%)
    val collisionSensitivity: Float,    // 衝突 (例: 3.0G)
    val soundSensitivity: Float,        // 大音量 (例: 85 dB)
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
    val decelerationSensitivity: Float = 1.5f,  // 減速までの秒数 (1.0s 〜 3.0s)
    val approachSensitivity: Float = 0.7f,      // 70%
    val collisionSensitivity: Float = 3.0f,     // 3.0G
    val soundSensitivity: Float = 85.0f         // 85 dB
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
    // 4種すべての危険度定量的指標 (件/km, 件/h)
    val decelerationPerKm: Float,
    val decelerationPerHour: Float,
    val approachPerKm: Float,
    val approachPerHour: Float,
    val collisionPerKm: Float,
    val collisionPerHour: Float,
    val soundPerKm: Float,
    val soundPerHour: Float
)
