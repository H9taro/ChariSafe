package jp.hisanet.charisafe.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 統合 ThresholdBar (スライダー + リアルタイム比較バー + ピークホールド)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThresholdBar(
    currentValue: Float,
    thresholdValue: Float,
    onThresholdChanged: (Float) -> Unit,
    minValue: Float,
    maxValue: Float,
    enabled: Boolean = true,
    enablePeakHold: Boolean = true,
    peakDecayFactor: Float = 0.97f,
    peakColor: Color = Color(0xFFFF9800),
    peakStrokeWidth: Dp = 2.dp
) {
    val safeMin = if (minValue.isFinite()) minValue else 0f
    val safeMax = if (maxValue.isFinite() && maxValue > safeMin) maxValue else (safeMin + 1f)
    val range = safeMax - safeMin

    val safeCurrentRaw = currentValue.takeIf { it.isFinite() } ?: safeMin
    val safeCurrent = safeCurrentRaw.coerceIn(safeMin, safeMax)

    val safeThresholdRaw = thresholdValue.takeIf { it.isFinite() } ?: safeMin
    val safeThreshold = safeThresholdRaw.coerceIn(safeMin, safeMax)

    val currentFraction = if (range > 0f) ((safeCurrent - safeMin) / range).coerceIn(0f, 1f) else 0f
    val isExceeding = safeCurrent >= safeThreshold

    var peakValue by remember { mutableFloatStateOf(0f) }
    val currentValueState by rememberUpdatedState(safeCurrent)

    LaunchedEffect(safeCurrent) {
        if (safeCurrent > peakValue) {
            peakValue = safeCurrent
        }
    }

    LaunchedEffect(enablePeakHold) {
        while (enablePeakHold) {
            delay(50L)
            peakValue = maxOf(currentValueState, peakValue * peakDecayFactor)
        }
    }

    if (safeMax > 1000f) {
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        android.util.Log.i("ChariSafeTrace", "$timeStr\nThresholdBar\ncurrentValue=${safeCurrent.toInt()}\nfraction=${String.format(Locale.US, "%.2f", currentFraction)}\nthresholdValue=${safeThreshold.toInt()}\npeakValue=${peakValue.toInt()}")
    }

    val activeTrackColor = if (isExceeding) Color.Red else MaterialTheme.colorScheme.primary
    val inactiveTrackColor = Color.DarkGray

    Slider(
        value = safeThreshold,
        onValueChange = onThresholdChanged,
        valueRange = safeMin..safeMax,
        enabled = enabled,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = remember { MutableInteractionSource() },
                colors = SliderDefaults.colors(thumbColor = Color.White)
            )
        },
        track = {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
            ) {
                val w = size.width
                val h = size.height
                val cornerRadius = h / 2f

                drawRoundRect(
                    color = inactiveTrackColor,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                )

                if (currentFraction > 0f) {
                    drawRoundRect(
                        color = activeTrackColor,
                        size = androidx.compose.ui.geometry.Size(w * currentFraction, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                    )
                }

                if (enablePeakHold && peakValue > safeMin) {
                    val peakFraction = if (range > 0f) ((peakValue - safeMin) / range).coerceIn(0f, 1f) else 0f
                    val peakX = w * peakFraction
                    drawLine(
                        color = peakColor,
                        start = Offset(peakX, 0f),
                        end = Offset(peakX, h),
                        strokeWidth = peakStrokeWidth.toPx()
                    )
                }
            }
        }
    )
}
