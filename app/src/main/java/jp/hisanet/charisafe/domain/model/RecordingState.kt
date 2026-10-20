package jp.hisanet.charisafe.domain.model

enum class RecordingState {
    IDLE,           // 録画停止中
    PREPARING,      // 録画準備・バインド中
    RECORDING,      // リングバッファ録画中
    EXPORTING,      // イベント動画保存出力中
    SLEEP,          // 自動スリープ中
    ERROR           // エラー状態
}
