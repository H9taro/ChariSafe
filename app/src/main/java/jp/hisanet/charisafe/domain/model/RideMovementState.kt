package jp.hisanet.charisafe.domain.model

/**
 * ライド中の移動状態
 *
 * MOVING  : 移動中
 * STOPPED : 停止中
 * PAUSED  : 一時停止（将来拡張用）
 */
enum class RideMovementState {
    MOVING,
    STOPPED,
    PAUSED
}