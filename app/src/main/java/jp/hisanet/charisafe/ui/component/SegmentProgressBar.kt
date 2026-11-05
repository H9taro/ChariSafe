package jp.hisanet.charisafe.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * 録画セグメント進捗バー（60秒周期）
 * 実際のセグメント開始時刻 / セッション開始時刻 (sessionStartTimeMs) を基準に経過時間を算出し、
 * アプリの再描画や画面回転（Configuration Change）でも進捗が正しく維持される設計。
 */
@Composable
fun SegmentProgressBar(
    isRecording: Boolean,
    sessionStartTimeMs: Long,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isRecording, sessionStartTimeMs) {
        if (!isRecording || sessionStartTimeMs <= 0L) {
            progress = 0f
            return@LaunchedEffect
        }
        while (true) {
            val now = System.currentTimeMillis()
            val elapsed = now - sessionStartTimeMs
            if (elapsed >= 0L) {
                val currentSegmentElapsed = elapsed % 60_000L
                progress = (currentSegmentElapsed / 60_000f).coerceIn(0f, 1f)
            } else {
                progress = 0f
            }
            delay(50.milliseconds) // 20fps update for smooth progress bar
        }
    }

    if (isRecording) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.DarkGray, RoundedCornerShape(2.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
        }
    }
}
