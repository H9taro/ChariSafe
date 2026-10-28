package jp.hisanet.charisafe.domain.model

/**
 * 保護決定および通知発報の理由
 */
enum class ProtectionReason(val description: String) {
    PREVIOUS_SEGMENT_AUTO_PROTECTION("一世代前セグメントの自動即時保護"),
    PENDING_PROTECTION_SET("現行セグメントの保護予約フラグ設定"),
    PENDING_PROTECTION_FINALIZED("予約済み現行セグメントのFinalize完了保護"),
    ALREADY_PROTECTED_SKIP("すでに保護済みのため追加コピーおよび通知をスキップ"),
    NOT_ELIGIBLE_SKIP("保護条件を満たさないため処理をスキップ"),
    MANUAL_EXPORT("手動操作によるエクスポート")
}

/**
 * 保護処理失敗理由
 */
enum class ProtectionFailureReason(val description: String) {
    DIRECTORY_CREATE_FAILED("保護先ディレクトリの作成失敗"),
    FILE_COPY_FAILED("ファイルコピー(I/O)エラー"),
    INSUFFICIENT_STORAGE("ストレージ空き容量不足"),
    MEDIA_SCAN_FAILED("MediaScanner登録失敗"),
    SOURCE_FILE_NOT_FOUND("保護元のセグメントファイルが見つからない")
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
