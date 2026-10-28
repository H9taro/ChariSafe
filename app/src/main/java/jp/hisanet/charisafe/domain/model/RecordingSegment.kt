package jp.hisanet.charisafe.domain.model

/**
 * 録画セグメント（ファイル世代物理モデル）
 */
data class RecordingSegment(
    val segmentName: String,               // 例: "seg_20261028_081000.mp4"
    val filePath: String,                  // リングバッファ内の絶対パス
    val startTimeMs: Long = System.currentTimeMillis(), // セグメント録画開始時刻
    var endTimeMs: Long? = null,            // セグメントFinalize完了時刻
    var isProtected: Boolean = false,       // すでに recordings/ へコピー・保護済みか
    var pendingProtection: Boolean = false // 次の Finalize 時に保護すべきかのフラグ
)
