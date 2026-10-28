package jp.hisanet.charisafe.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import jp.hisanet.charisafe.domain.model.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
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
            delay(2000L)
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
                    label = { Text("📈 ライド結果", fontSize = 18.sp) },
                    selected = uiState.currentScreen == Screen.RIDE_RESULTS,
                    onClick = {
                        viewModel.setScreen(Screen.RIDE_RESULTS)
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
                            Screen.RIDE_RESULTS -> "ライド結果"
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
                    Screen.RIDE_RESULTS -> RideResultContent(uiState)
                    Screen.SETTINGS -> SettingsContent(
                        viewModel = viewModel,
                        uiState = uiState,
                        onShowDeleteDialog = { showDeleteConfirmDialog = true },
                        onShowShareModal = { showShareModal = true }
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

        Spacer(modifier = Modifier.height(16.dp))

        // 2. 感度スライダー 4種 ＆ リアルタイム比較バー
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 接近
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "接近 ${(uiState.sensitivity.approachSensitivity * 100).toInt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${uiState.sessionEventCounts.approachCount}件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Slider(
                        value = uiState.sensitivity.approachSensitivity,
                        onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(approachSensitivity = it)) },
                        valueRange = 0.3f..1.0f,
                        steps = 7
                    )
                    RealtimeComparisonBar(
                        currentValue = uiState.currentApproachScore,
                        thresholdValue = uiState.sensitivity.approachSensitivity * 100f,
                        minValue = 30f,
                        maxValue = 100f
                    )
                }

                // 減速
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "減速 ${String.format(Locale.US, "%.1f", uiState.sensitivity.decelerationSensitivity)} m/s²", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${uiState.sessionEventCounts.decelerationCount}件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Slider(
                        value = uiState.sensitivity.decelerationSensitivity,
                        onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(decelerationSensitivity = it)) },
                        valueRange = 1.0f..4.0f,
                        steps = 29
                    )
                    RealtimeComparisonBar(
                        currentValue = uiState.currentDecelValue,
                        thresholdValue = uiState.sensitivity.decelerationSensitivity,
                        minValue = 1.0f,
                        maxValue = 4.0f
                    )
                }

                // 衝撃
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "衝撃 ${String.format(Locale.US, "%.1f", uiState.sensitivity.collisionSensitivity)}G", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${uiState.sessionEventCounts.collisionCount}件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Slider(
                        value = uiState.sensitivity.collisionSensitivity,
                        onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(collisionSensitivity = it)) },
                        valueRange = 2.0f..5.0f,
                        steps = 15
                    )
                    RealtimeComparisonBar(
                        currentValue = uiState.currentGValue,
                        thresholdValue = uiState.sensitivity.collisionSensitivity,
                        minValue = 2.0f,
                        maxValue = 5.0f
                    )
                }

                // 音量
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "音量 ${uiState.sensitivity.soundSensitivity.toInt()}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${uiState.sessionEventCounts.soundCount}件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Slider(
                        value = uiState.sensitivity.soundSensitivity,
                        onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(soundSensitivity = it)) },
                        valueRange = 1000f..10000f,
                        steps = 18
                    )
                    RealtimeComparisonBar(
                        currentValue = uiState.currentSoundAmplitude,
                        thresholdValue = uiState.sensitivity.soundSensitivity,
                        minValue = 1000f,
                        maxValue = 10000f
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
 * リアルタイム比較バー
 */
@Composable
fun RealtimeComparisonBar(
    currentValue: Float,
    thresholdValue: Float,
    minValue: Float,
    maxValue: Float
) {
    val fraction = ((currentValue - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
    val thresholdFraction = ((thresholdValue - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
    val isExceeding = currentValue >= thresholdValue

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(Color.DarkGray, RoundedCornerShape(5.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(if (isExceeding) Color.Red else MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = size.width * thresholdFraction
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 4f
            )
        }
    }
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
                                        val targetFile = resolveTargetFile(context, inc)
                                        if (targetFile != null) {
                                            try {
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "video/mp4")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                android.util.Log.e("HistoryContent", "Failed to play video", e)
                                            }
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🎥 動画  ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(inc.timestampMs))}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
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
                                            val uri = Uri.parse("https://maps.google.com/?q=${det.latitude},${det.longitude}")
                                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {}
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
                                    val cleanSensitivity = det.sensitivityLabel
                                        .replace("感度:", "")
                                        .replace("振幅:", "")
                                        .replace("G:", "")
                                        .replace("減速:", "")
                                        .trim()
                                    val speedStr = String.format(Locale.US, "%.1f km/h", det.speedKmh)
                                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(det.timestampMs))
                                    val displayText = "${det.eventType.emoji} ${det.eventType.displayName}  $timeStr  |  $cleanSensitivity  |  $speedStr"

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
 * 📈 ライド結果画面
 */
@Composable
fun RideResultContent(uiState: MainUiState) {
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

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "【危険度定量的指標】", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                    
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(text = "🚗 接近: ${target.eventCounts.approachCount}件", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "   • 距離あたり: ${String.format(Locale.US, "%.2f", target.approachPerKm)} 件/km", fontSize = 16.sp, color = Color.LightGray)
                        Text(text = "   • 時間あたり: ${String.format(Locale.US, "%.1f", target.approachPerHour)} 件/h", fontSize = 16.sp, color = Color.LightGray)
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(text = "⚠ 減速: ${target.eventCounts.decelerationCount}件", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "   • 距離あたり: ${String.format(Locale.US, "%.2f", target.decelerationPerKm)} 件/km", fontSize = 16.sp, color = Color.LightGray)
                        Text(text = "   • 時間あたり: ${String.format(Locale.US, "%.1f", target.decelerationPerHour)} 件/h", fontSize = 16.sp, color = Color.LightGray)
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(text = "💥 衝撃: ${target.eventCounts.collisionCount}件", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "   • 距離あたり: ${String.format(Locale.US, "%.2f", target.collisionPerKm)} 件/km", fontSize = 16.sp, color = Color.LightGray)
                        Text(text = "   • 時間あたり: ${String.format(Locale.US, "%.1f", target.collisionPerHour)} 件/h", fontSize = 16.sp, color = Color.LightGray)
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(text = "🔊 音量: ${target.eventCounts.soundCount}件", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "   • 距離あたり: ${String.format(Locale.US, "%.2f", target.soundPerKm)} 件/km", fontSize = 16.sp, color = Color.LightGray)
                        Text(text = "   • 時間あたり: ${String.format(Locale.US, "%.1f", target.soundPerHour)} 件/h", fontSize = 16.sp, color = Color.LightGray)
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
    uiState: MainUiState,
    onShowDeleteDialog: () -> Unit,
    onShowShareModal: () -> Unit
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

        // 2. ライドデータ
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(text = "ライドデータ", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("CSVデバッグ出力", fontSize = 16.sp, color = Color.White)
                    Switch(
                        checked = config.approachCsvEnabled,
                        onCheckedChange = { viewModel.updateApproachCsvEnabled(it) },
                        enabled = !isRecording
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
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

private fun resolveTargetFile(context: android.content.Context, inc: IncidentLog): File? {
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
    val recDir = jp.hisanet.charisafe.util.FileUtils.getRecordingsDir(context)
    val found = recDir.listFiles { _, name -> name.endsWith(".mp4") }
        ?.sortedByDescending { it.lastModified() }
        ?.firstOrNull()
    return found
}
