package jp.hisanet.charisafe.ui.model

data class EventUiDefinition(
    val displayName: String,
    val emoji: String,
    val format: String,
    val unitName: String,
    val sliderMin: Float,
    val sliderMax: Float
)
