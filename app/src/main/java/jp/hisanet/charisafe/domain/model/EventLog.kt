package jp.hisanet.charisafe.domain.model

import java.util.UUID

enum class EventType(val displayName: String, val emoji: String) {
    DECELERATION("減速", "⚠"),
    APPROACH("接近", "🚗"),
    COLLISION("衝撃", "💥"),
    SOUND("音量", "🔊")
}

data class EventDetectionLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val speedKmh: Float,
    val latitude: Double,
    val longitude: Double,
    val sensitivityValue: Float,           // 生数値
    val sensitivityLabel: String,          // フォーマット済み表示文字列 ("感度: 70%", "1.5秒", "3.0G", "振幅: 5000")
    val sensitivitySnapshot: EventSensitivity = EventSensitivity(),
    var incidentId: String? = null         // 紐付けられた IncidentLog ID
)

enum class IncidentStatus(val displayName: String) {
    PENDING("保存中..."),   // MP4セグメント抽出・結合処理中
    SUCCESS("保存完了"),   // MP4動画の生成・メディアスキャン完了
    FAILED("保存失敗")     // エラー（ディスク満杯、ファイル破損、OOM等）
}

data class IncidentLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val triggerEventType: EventType,
    val triggerDetectionLogId: String,
    var relatedDetectionIds: List<String> = emptyList(),  // Lock保持期間中に起きた全 EventDetectionLog ID
    var highestSeverityType: EventType = triggerEventType, // 最重要（最高危険度）イベントタイプ
    val latitude: Double,
    val longitude: Double,
    var videoFilePath: String = "",
    var status: IncidentStatus = IncidentStatus.PENDING,
    var failureReason: String? = null
)

data class EventCountSummary(
    val decelerationCount: Int = 0,
    val approachCount: Int = 0,
    val collisionCount: Int = 0,
    val soundCount: Int = 0
)

data class EventSensitivity(
    val decelerationSensitivity: Float = 1.5f,  // 減速までの秒数
    val approachSensitivity: Float = 0.7f,      // 接近検知
    val collisionSensitivity: Float = 3.0f,     // 衝撃 G
    val soundSensitivity: Float = 5000.0f       // 音量振幅
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
    val decelerationPerKm: Float,
    val decelerationPerHour: Float,
    val approachPerKm: Float,
    val approachPerHour: Float,
    val collisionPerKm: Float,
    val collisionPerHour: Float,
    val soundPerKm: Float,
    val soundPerHour: Float
)
