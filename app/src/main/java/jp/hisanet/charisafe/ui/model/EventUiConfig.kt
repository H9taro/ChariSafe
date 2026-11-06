package jp.hisanet.charisafe.ui.model

import jp.hisanet.charisafe.domain.model.EventType
import java.util.Locale

object EventUiConfig {

    fun get(eventType: EventType): EventUiDefinition {
        return when (eventType) {
            EventType.APPROACH ->
                EventUiDefinition(
                    displayName = "接近",
                    emoji = "🚗",
                    format = "%.2f",
                    unitName = "",
                    sliderMin = 0f,
                    sliderMax = 5f
                )

            EventType.DECELERATION ->
                EventUiDefinition(
                    displayName = "減速",
                    emoji = "⚠",
                    format = "%.2f",
                    unitName = " m/s²",
                    sliderMin = 0f,
                    sliderMax = 3f
                )

            EventType.COLLISION ->
                EventUiDefinition(
                    displayName = "衝撃",
                    emoji = "💥",
                    format = "%.2f",
                    unitName = " G",
                    sliderMin = 1f,
                    sliderMax = 5f
                )

            EventType.SOUND ->
                EventUiDefinition(
                    displayName = "音量",
                    emoji = "🔊",
                    format = "%.2f",
                    unitName = "",
                    sliderMin = 0f,
                    sliderMax = 2f
                )
        }
    }

    fun formatValue(eventType: EventType, value: Float): String {
        return String.format(Locale.US, get(eventType).format, value)
    }

    fun formatMetricText(eventType: EventType, currentValue: Float, thresholdValue: Float): String {
        val def = get(eventType)
        val curStr = formatValue(eventType, currentValue)
        val thStr = formatValue(eventType, thresholdValue)
        return "$curStr / $thStr${def.unitName}"
    }
}
