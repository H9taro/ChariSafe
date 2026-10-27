package jp.hisanet.charisafe.domain.model

import java.util.UUID

data class RideSession(
    val id: String = UUID.randomUUID().toString(),
    val startTimeMs: Long = System.currentTimeMillis(),
    var isRecordingVideo: Boolean = true,
    val recordedPoints: MutableList<GpxPoint> = mutableListOf(),
    val events: MutableList<EventDetectionLog> = mutableListOf(),
    val incidents: MutableList<IncidentLog> = mutableListOf()
) {
    // 継続時間は常に現在時刻との差分でリアルタイム算出 (endTimeMs なし)
    val totalActiveDurationSec: Long
        get() = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(0L)

    // 累積件数
    val approachCount: Int get() = events.count { it.eventType == EventType.APPROACH }
    val decelerationCount: Int get() = events.count { it.eventType == EventType.DECELERATION }
    val collisionCount: Int get() = events.count { it.eventType == EventType.COLLISION }
    val soundCount: Int get() = events.count { it.eventType == EventType.SOUND }
}
