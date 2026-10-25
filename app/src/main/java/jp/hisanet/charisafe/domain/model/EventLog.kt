package jp.hisanet.charisafe.domain.model

import java.util.UUID

enum class EventType(val displayName: String, val emoji: String) {
    DECELERATION("減速", "⚠"),
    APPROACH("接近", "🚗"),
    COLLISION("衝撃", "💥"),
    SOUND("音量", "🔊")
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
    val collisionSensitivity: Float,    // 衝撃 (例: 3.0G)
    val soundSensitivity: Float,        // 音量 (例: 85 dB)
    val videoFilePath: String? = null
)

data class EventCountSummary(
    val decelerationCount: Int = 0,
    val approachCount: Int = 0,
    val collisionCount: Int = 0,
    val soundCount: Int = 0
)

data class EventSensitivity(
    val decelerationSensitivity: Float = 1.5f,  // 減速までの秒数 (左=敏感 0.5s 〜 右=鈍感 3.0s)
    val approachSensitivity: Float = 0.7f,      // 接近検知 (左=敏感 30% 〜 右=鈍感 100%)
    val collisionSensitivity: Float = 3.0f,     // 衝撃 G (左=敏感 2.0G 〜 右=鈍感 5.0G)
    val soundSensitivity: Float = 85.0f         // 音量 dB (左=敏感 70dB 〜 右=鈍感 100dB)
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
    // 4種すべての危険度定量的指標 (件/km, 件/h) - 順序統一
    val decelerationPerKm: Float,
    val decelerationPerHour: Float,
    val approachPerKm: Float,
    val approachPerHour: Float,
    val collisionPerKm: Float,
    val collisionPerHour: Float,
    val soundPerKm: Float,
    val soundPerHour: Float
)
