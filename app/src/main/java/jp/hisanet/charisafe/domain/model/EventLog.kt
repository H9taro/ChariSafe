package jp.hisanet.charisafe.domain.model

import java.util.UUID

data class EventDetectionLog(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val speedKmh: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val detectedValue: Float = 0f,     // 実際の検知値
    val thresholdValue: Float = 0f,    // 当時の設定閾値
    val displayLabel: String = "",      // 表示補助
    var incidentId: String? = null,     // 紐付けられた IncidentLog ID
    var segmentName: String = ""        // 発生時に録画中だったセグメント名
)

enum class IncidentStatus {
    PENDING,   // セグメント退避・メディアスキャン中
    SUCCESS,       // 動画セグメントの保護・メディアスキャン完了
    FAILED         // エラー（ディスク満杯、ファイルコピー失敗等）
}

data class IncidentLog(
    val id: String = UUID.randomUUID().toString(),
    var timestampMs: Long = System.currentTimeMillis(),
    val triggerEventType: EventType,
    val triggerDetectionLogId: String,
    var relatedDetectionIds: List<String> = emptyList(),  // Lock保持期間中に起きた全 EventDetectionLog ID
    var highestSeverityType: EventType = triggerEventType, // 最重要（最高危険度）イベントタイプ
    val latitude: Double,
    val longitude: Double,
    var videoFilePath: String = "",
    var coveredSegmentFiles: List<String> = emptyList(), // カバーする保護動画セグメントファイルパス一覧
    var status: IncidentStatus = IncidentStatus.PENDING,
    var failureReason: String? = null
)

data class EventSensitivity(
    val decelerationSensitivity: Float = 1.5f,  // 減速までの秒数
    val approachSensitivity: Float = 0.7f,      // 接近検知
    val collisionSensitivity: Float = 3.0f,     // 衝撃 G
    val soundSensitivity: Float = 0.6f          // 音量振幅 (0〜2)
)

data class RideSummary(
    val id: String = UUID.randomUUID().toString(),
    val gpxFileName: String = "",
    val isCompleted: Boolean = false,
    val lastAggregatedTimestampMs: Long = 0L,
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
