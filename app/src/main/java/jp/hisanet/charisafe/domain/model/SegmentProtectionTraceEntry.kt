package jp.hisanet.charisafe.domain.model

/**
 * 保護決定および通知発報の理由
 */
enum class ProtectionReason() {
    PREVIOUS_SEGMENT_AUTO_PROTECTION(),
    PENDING_PROTECTION_SET(),
    PENDING_PROTECTION_FINALIZED(),
    ALREADY_PROTECTED_SKIP(),
}

/**
 * 保護処理失敗理由
 */
enum class ProtectionFailureReason() {
    FILE_COPY_FAILED(),
}

/**
 * 系統 B: セグメント保護・通知決定のトレースエントリ
 */
data class SegmentProtectionTraceEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val segmentName: String,                  // 対象セグメント名 (例: "seg_20261028_081000.mp4")
    val isProtected: Boolean,                  // 現在の物理保護状態
    val pendingProtection: Boolean,            // 現在の保護予約状態
    val triggerEventType: String,             // トリガーイベント名
    val protectionReason: ProtectionReason,    // 決定理由
    val notificationSent: Boolean,             // 通知を発報したか否か
    val operationSucceeded: Boolean = true,   // 処理成否 (true: 成功, false: 失敗)
    val failureReason: ProtectionFailureReason? = null, // 失敗理由
    val associatedEventId: String? = null      // トリガーとなった EventDetectionLog ID
)
