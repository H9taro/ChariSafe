package jp.hisanet.charisafe.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.hisanet.charisafe.ui.model.EventMetricUiModel
import jp.hisanet.charisafe.ui.model.EventUiConfig

@Composable
fun EventMetricCard(
    metric: EventMetricUiModel,
    onThresholdChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiDef = EventUiConfig.get(metric.eventType)
    val metricText =
        EventUiConfig.formatMetricText(metric.eventType, metric.currentValue, metric.thresholdValue)

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${uiDef.displayName}  $metricText",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${metric.eventCount}件",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                ThresholdBar(
                    currentValue = metric.currentValue,
                    thresholdValue = metric.thresholdValue,
                    onThresholdChanged = onThresholdChanged,
                    minValue = uiDef.sliderMin,
                    maxValue = uiDef.sliderMax
                )
            }
        }
    }
}
