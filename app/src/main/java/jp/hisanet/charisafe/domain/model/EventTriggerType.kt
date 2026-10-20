package jp.hisanet.charisafe.domain.model

enum class EventTriggerType {
    MANUAL,         // ユーザー手動押下
    IMPACT,         // 衝撃検知 (Phase 2)
    APPROACH,       // 車両接近検知 (Phase 3)
    FALL            // 転倒検知 (Phase 2)
}
