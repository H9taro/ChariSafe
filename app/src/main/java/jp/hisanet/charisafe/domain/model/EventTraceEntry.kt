package jp.hisanet.charisafe.domain.model

/**
 * 系統 A: イベント判定トレースエントリ (センサー測定推移・判定ログ)
 */
data class EventTraceEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val segmentName: String,           // 対象録画セグメント名 (例: "seg_20261028_081000.mp4")
    val eventType: EventType,
    val currentValue: Float,           // 判定対象値 (減速度 m/s², G値, 音量振幅, 接近スコア)
    val thresholdValue: Float,         // ユーザー設定の閾値
    val rawValue: Float = 0f,          // 補足生値
    val smoothedValue: Float = 0f,     // EMA平滑化後速度など
    val consecutiveCount: Int = 0,     // 連続判定回数
    val isMuted: Boolean = false,      // 一時ミュート状態
    val isTriggered: Boolean           // 検知判定結果 (true: 発火)
)
