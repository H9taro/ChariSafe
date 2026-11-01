package jp.hisanet.charisafe.ui.main

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import jp.hisanet.charisafe.data.debug.TraceLayer
import jp.hisanet.charisafe.data.debug.TraceLogger
import jp.hisanet.charisafe.data.debug.TraceMetric
import jp.hisanet.charisafe.domain.model.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var highlightedEventId by remember { mutableStateOf<String?>(null) }
    val historyListState = rememberLazyListState()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showShareModal by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.targetEventIdToHighlight) {
        val targetId = uiState.targetEventIdToHighlight
        if (targetId != null) {
            highlightedEventId = targetId
            delay(2000L.milliseconds)
            highlightedEventId = null
            viewModel.setTargetEventIdToHighlight(null)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "ChariSafe メニュー",
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("🏠 ホーム", fontSize = 18.sp) },
                    selected = uiState.currentScreen == Screen.MAIN,
                    onClick = {
                        viewModel.setScreen(Screen.MAIN)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("📁 イベント履歴", fontSize = 18.sp) },
                    selected = uiState.currentScreen == Screen.HISTORY,
                    onClick = {
                        viewModel.setScreen(Screen.HISTORY)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("📈 ライドサマリ", fontSize = 18.sp) },
                    selected = uiState.currentScreen == Screen.RIDE_SUMMARY,
                    onClick = {
                        viewModel.setScreen(Screen.RIDE_SUMMARY)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("⚙️ 設定", fontSize = 18.sp) },
                    selected = uiState.currentScreen == Screen.SETTINGS,
                    onClick = {
                        viewModel.setScreen(Screen.SETTINGS)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val title = when (uiState.currentScreen) {
                            Screen.MAIN -> "ChariSafe ライドモニター"
                            Screen.HISTORY -> "イベント履歴"
                            Screen.RIDE_SUMMARY -> "ライドサマリ"
                            Screen.SETTINGS -> "設定"
                        }
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    },
                    navigationIcon = {
                        if (uiState.currentScreen == Screen.MAIN) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Text(text = "☰", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            IconButton(onClick = { viewModel.setScreen(Screen.MAIN) }) {
                                Text(text = "←", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (uiState.currentScreen) {
                    Screen.MAIN -> MainContent(viewModel, uiState)
                    Screen.HISTORY -> HistoryContent(uiState, highlightedEventId, historyListState)
                    Screen.RIDE_SUMMARY -> RideSummaryContent(
                        uiState,
                        onShowDeleteDialog = { showDeleteConfirmDialog = true },
                        onShowShareModal = { showShareModal = true }
                    )
                    Screen.SETTINGS -> SettingsContent(
                        viewModel = viewModel,
                        uiState = uiState
                    )
                }
            }
        }
    }

    // 削除確認ダイアログ
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("⚠️ 現在ライドのデータ削除", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = { Text("現在のライドデータ（動画・ログ・サマリー）をすべて削除して初期状態に戻しますか？", fontSize = 16.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCurrentRide()
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("削除実行", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("キャンセル", fontSize = 16.sp)
                }
            }
        )
    }

    // データ共有選択モーダル
    if (showShareModal) {
        AlertDialog(
            onDismissRequest = { showShareModal = false },
            title = { Text("📦 ライドデータを共有", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { viewModel.shareAllData(); showShareModal = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("すべて共有 (ZIP)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { viewModel.shareLogsOnly(); showShareModal = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("ログのみ共有", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { viewModel.shareVideosOnly(); showShareModal = false }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("動画のみ共有", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showShareModal = false }) {
                    Text("閉じる", fontSize = 16.sp)
                }
            }
        )
    }

    // ZIP生成中ローディングダイアログ
    if (uiState.isZipGenerating) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("📦 ZIPファイル生成中", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Text("動画およびログを圧縮しています...\nしばらくお待ちください。", fontSize = 15.sp)
                }
            },
            confirmButton = {}
        )
    }
}

/**
 * 🏠 ホーム画面
 */
@Composable
fun MainContent(viewModel: MainViewModel, uiState: MainUiState) {
    val isRecording = uiState.status.state == RecordingState.RECORDING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. 速度メーター
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f", uiState.status.currentSpeedKmh),
                    fontSize = 90.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "km/h",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }
        }

        // 2. 感度スライダー 4種 ＆ リアルタイム比較バー
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 接近
                Column {
                    android.util.Log.i("ApproachTrace","UI=${uiState.currentApproachScore}")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "接近 ${String.format(Locale.US, "%.1f", uiState.sensitivity.approachSensitivity)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${uiState.sessionEventCounts.approachCount}件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    ThresholdBar(
                        currentValue = uiState.currentApproachScore,
                        thresholdValue = uiState.sensitivity.approachSensitivity,
                        onThresholdChanged = { viewModel.updateSensitivity(uiState.sensitivity.copy(approachSensitivity = it)) },
                        minValue = 0f,
                        maxValue = 20f
                    )
                }

                // 減速
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "減速 ${String.format(Locale.US, "%.2f", uiState.sensitivity.decelerationSensitivity)} m/s²", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${uiState.sessionEventCounts.decelerationCount}件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    ThresholdBar(
                        currentValue = uiState.currentDecelValue,
                        thresholdValue = uiState.sensitivity.decelerationSensitivity,
                        onThresholdChanged = { viewModel.updateSensitivity(uiState.sensitivity.copy(decelerationSensitivity = it)) },
                        minValue = 0f,
                        maxValue = 2f
                    )
                }

                // 衝撃
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "衝撃 ${String.format(Locale.US, "%.1f", uiState.sensitivity.collisionSensitivity)}G", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${uiState.sessionEventCounts.collisionCount}件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    ThresholdBar(
                        currentValue = uiState.currentGValue,
                        thresholdValue = uiState.sensitivity.collisionSensitivity,
                        onThresholdChanged = { viewModel.updateSensitivity(uiState.sensitivity.copy(collisionSensitivity = it)) },
                        minValue = 0f,
                        maxValue = 5f
                    )
                }

                // 音量
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "音量 ${(uiState.sensitivity.soundSensitivity / 1.5f * 100).toInt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${uiState.sessionEventCounts.soundCount}件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    ThresholdBar(
                        currentValue = uiState.currentSoundAmplitude,
                        thresholdValue = uiState.sensitivity.soundSensitivity,
                        onThresholdChanged = { viewModel.updateSensitivity(uiState.sensitivity.copy(soundSensitivity = it)) },
                        minValue = 0f,
                        maxValue = 1.5f
                    )
                }
            }
        }

        // 3. シャッターボタン
        CameraShutterButton(isRecording = isRecording) {
            if (isRecording) viewModel.stopRecording() else viewModel.startRecording()
        }
    }
}

/**
 * シャッターボタン形状
 */
@Composable
fun CameraShutterButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(Color.White, CircleShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
            shape = CircleShape,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp)
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

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
    // 異常値対策
    val safeMin = if (minValue.isFinite()) minValue else 0f
    val safeMax = if (maxValue.isFinite() && maxValue > safeMin) maxValue else (safeMin + 1f)
    val range = safeMax - safeMin

    val safeCurrentRaw = currentValue.takeIf { it.isFinite() } ?: safeMin
    val safeCurrent = safeCurrentRaw.coerceIn(safeMin, safeMax)

    val safeThresholdRaw = thresholdValue.takeIf { it.isFinite() } ?: safeMin
    val safeThreshold = safeThresholdRaw.coerceIn(safeMin, safeMax)

    val currentFraction = if (range > 0f) ((safeCurrent - safeMin) / range).coerceIn(0f, 1f) else 0f
    val isExceeding = safeCurrent >= safeThreshold

    // ピークホールド状態管理 (rememberUpdatedStateを活用して最新値を安全に参照)
    var peakValue by remember { mutableFloatStateOf(0f) }
    val currentValueState by rememberUpdatedState(safeCurrent)

    LaunchedEffect(safeCurrent) {
        if (safeCurrent > peakValue) {
            peakValue = safeCurrent
        }
    }

    LaunchedEffect(enablePeakHold) {
        while (enablePeakHold) {
            delay(50L.milliseconds)
            peakValue = maxOf(currentValueState, peakValue * peakDecayFactor)
        }
    }

    val context = LocalContext.current
    val metric = when {
        safeMax <= 1f -> TraceMetric.SOUND
        safeMax <= 5f -> TraceMetric.COLLISION
        else -> TraceMetric.APPROACH
    }
    TraceLogger.getInstance(context).log(metric, TraceLayer.UI, safeCurrent)

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

                // 背景トラック
                drawRoundRect(
                    color = inactiveTrackColor,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                )

                // リアルタイム進捗トラック (現在値)
                if (currentFraction > 0f) {
                    drawRoundRect(
                        color = activeTrackColor,
                        size = androidx.compose.ui.geometry.Size(w * currentFraction, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                    )
                }

                // ピークホールド縦線 (オレンジ色の細線)
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

/**
 * 📁 イベント履歴画面
 */
@Composable
fun HistoryContent(uiState: MainUiState, highlightedEventId: String?, listState: androidx.compose.foundation.lazy.LazyListState) {
    val context = LocalContext.current
    val grouped = uiState.groupedTimelineItems

    if (grouped.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("イベント履歴はありません", fontSize = 18.sp, color = Color.Gray)
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            grouped.forEach { (dateStr, items) ->
                item {
                    Text(
                        text = "📅 $dateStr",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                items(items, key = {
                    when (it) {
                        is TimelineItem.Recording -> it.incidentLog.id
                        is TimelineItem.Detection -> it.detectionLog.id
                    }
                }) { item ->
                    when (item) {
                        is TimelineItem.Recording -> {
                            val inc = item.incidentLog
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val targetFile = resolveTargetFile(inc)
                                        if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
                                            try {
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "video/mp4")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                android.util.Log.e("HistoryContent", "Failed to play video", e)
                                                android.widget.Toast.makeText(context, "動画の再生に失敗しました: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            android.widget.Toast.makeText(context, "指定された動画ファイルが見つかりません（削除または移動済みの可能性があります）", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "🎥 動画  ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(inc.timestampMs))}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val statusText = if (inc.status == IncidentStatus.PENDING) " (保護中...)" else ""
                                    Text(text = "トリガー: ${inc.triggerEventType.emoji} ${inc.triggerEventType.displayName}$statusText", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        is TimelineItem.Detection -> {
                            val det = item.detectionLog
                            val isHighlighted = (det.id == highlightedEventId)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        try {
                                            val uri =
                                                "https://maps.google.com/?q=${det.latitude},${det.longitude}".toUri()
                                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isHighlighted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = if (isHighlighted) 6.dp else 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val sensitivity = det.sensitivityLabel
                                    val speedStr = String.format(Locale.US, "%.1f km/h", det.speedKmh)
                                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(det.timestampMs))
                                    val displayText = "${det.eventType.emoji} ${det.eventType.displayName}  $timeStr  |  $sensitivity  |  $speedStr"

                                    Text(
                                        text = displayText,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 📈 ライドサマリ画面
 */
@Composable
fun RideSummaryContent(
        uiState: MainUiState,
        onShowDeleteDialog: () -> Unit,
        onShowShareModal: () -> Unit
    ) {
    val target = uiState.currentRideSummary

    if (target == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("現在進行中のライドデータはありません", fontSize = 18.sp, color = Color.Gray)
        }
    } else {
        val movingMin = target.movingTimeSec / 60
        val movingSec = target.movingTimeSec % 60
        val stoppedMin = target.stoppedTimeSec / 60
        val stoppedSec = target.stoppedTimeSec % 60

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "走行時間: ${movingMin}分 ${movingSec}秒", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "停止時間: ${stoppedMin}分 ${stoppedSec}秒", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    
                    Text(text = "総距離: ${String.format(Locale.US, "%.2f", target.totalDistanceKm)} km", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    
                    Text(text = "平均速度: ${String.format(Locale.US, "%.1f", target.averageSpeedKmh)} km/h", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "最高速度: ${String.format(Locale.US, "%.1f", target.maxSpeedKmh)} km/h", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)

                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(text = "🚗 接近: ${target.eventCounts.approachCount}件 ${
                            String.format(
                                Locale.US,
                                "%.1f",
                                target.approachPerHour
                            )
                        } 件/h", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(text = "⚠ 減速: ${target.eventCounts.decelerationCount}件 ${
                            String.format(
                                Locale.US,
                                "%.1f",
                                target.decelerationPerHour
                            )
                        } 件/h", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(text = "💥 衝撃: ${target.eventCounts.collisionCount}件 ${
                            String.format(
                                Locale.US,
                                "%.1f",
                                target.collisionPerHour
                            )
                        } 件/h", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(text = "🔊 音量: ${target.eventCounts.soundCount}件 ${
                            String.format(
                                Locale.US,
                                "%.1f",
                                target.soundPerHour
                            )
                        } 件/h", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onShowShareModal,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            ) {
                                Text("共有", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onShowDeleteDialog,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("削除", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ⚙️ 設定画面（画質・リングバッファ時間・GPS間隔完全対応・1秒追加・詳細案内）
 */
@Composable
fun SettingsContent(
    viewModel: MainViewModel,
    uiState: MainUiState
) {
    val config = uiState.config
    val isRecording = uiState.status.state == RecordingState.RECORDING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isRecording) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠️ 録画中のため、設定の変更はロックされています（停止後に変更可能）",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // 1. 撮影設定
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(text = "撮影設定 (Camera & Resolution)", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                
                // アクティブカメラ
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("アクティブカメラ", fontSize = 16.sp, color = Color.White)
                    var cameraExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { cameraExpanded = true }, enabled = !isRecording) {
                            Text(if (config.activeCamera == ActiveCamera.REAR) "後方(リア)" else "前方(フロント)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        DropdownMenu(expanded = cameraExpanded, onDismissRequest = { cameraExpanded = false }) {
                            DropdownMenuItem(text = { Text("後方(リア)", fontSize = 16.sp) }, onClick = { viewModel.updateActiveCamera(ActiveCamera.REAR); cameraExpanded = false })
                            DropdownMenuItem(text = { Text("前方(フロント)", fontSize = 16.sp) }, onClick = { viewModel.updateActiveCamera(ActiveCamera.FRONT); cameraExpanded = false })
                        }
                    }
                }

                // フレームレート (FPS)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("フレームレート (FPS)", fontSize = 16.sp, color = Color.White)
                    var fpsExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { fpsExpanded = true }, enabled = !isRecording) {
                            Text("${config.fps} fps", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        DropdownMenu(expanded = fpsExpanded, onDismissRequest = { fpsExpanded = false }) {
                            listOf(5, 10, 15).forEach { f ->
                                DropdownMenuItem(text = { Text("$f fps", fontSize = 16.sp) }, onClick = { viewModel.updateFps(f); fpsExpanded = false })
                            }
                        }
                    }
                }

                // 録画解像度 (640x480, 1280x720, 1920x1080 選択可能)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("録画解像度", fontSize = 16.sp, color = Color.White)
                    var resExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { resExpanded = true }, enabled = !isRecording) {
                            Text("${config.resolution.width}x${config.resolution.height}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        DropdownMenu(expanded = resExpanded, onDismissRequest = { resExpanded = false }) {
                            VideoResolution.entries.forEach { res ->
                                DropdownMenuItem(
                                    text = { Text("${res.width}x${res.height}", fontSize = 16.sp) },
                                    onClick = {
                                        viewModel.updateResolution(res)
                                        resExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // リングバッファ時間
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("リングバッファ保持時間", fontSize = 16.sp, color = Color.White)
                    var rbExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { rbExpanded = true }, enabled = !isRecording) {
                            Text("${config.ringBufferMinutes} 分", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        DropdownMenu(expanded = rbExpanded, onDismissRequest = { rbExpanded = false }) {
                            listOf(3, 5, 10).forEach { min ->
                                DropdownMenuItem(text = { Text("$min 分", fontSize = 16.sp) }, onClick = { viewModel.updateRingBuffer(min); rbExpanded = false })
                            }
                        }
                    }
                }

                // GPS記録間隔 (1秒を追加)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("GPS記録間隔", fontSize = 16.sp, color = Color.White)
                    var gpsExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { gpsExpanded = true }, enabled = !isRecording) {
                            Text("${config.gpsIntervalSeconds} 秒", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        DropdownMenu(expanded = gpsExpanded, onDismissRequest = { gpsExpanded = false }) {
                            listOf(1L, 5L, 10L, 30L, 60L).forEach { sec ->
                                DropdownMenuItem(
                                    text = { Text("$sec 秒", fontSize = 16.sp) },
                                    onClick = {
                                        viewModel.updateGpsInterval(sec)
                                        gpsExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveTargetFile(inc: IncidentLog): File? {
    if (inc.coveredSegmentFiles.isNotEmpty()) {
        for (path in inc.coveredSegmentFiles) {
            val f = File(path)
            if (f.exists() && f.length() > 0) return f
        }
    }
    if (inc.videoFilePath.isNotEmpty()) {
        val f = File(inc.videoFilePath)
        if (f.exists() && f.length() > 0) return f
    }
    return null
}
