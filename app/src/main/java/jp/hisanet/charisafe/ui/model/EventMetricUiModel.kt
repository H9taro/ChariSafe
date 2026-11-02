package jp.hisanet.charisafe.ui.model

import jp.hisanet.charisafe.domain.model.EventType

data class EventMetricUiModel(
    val eventType: EventType,
    val currentValue: Float,
    val thresholdValue: Float,
    val eventCount: Int
)
