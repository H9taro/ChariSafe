package jp.hisanet.charisafe.domain.model

enum class RecordingState {
    IDLE,           // 録画停止中
    RECORDING,      // リングバッファ録画中
    EXPORTING,      // イベント動画保存出力中
}
