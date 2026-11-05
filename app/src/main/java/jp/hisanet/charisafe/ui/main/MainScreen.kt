package jp.hisanet.charisafe.ui.main

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import jp.hisanet.charisafe.domain.model.ActiveCamera
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.IncidentLog
import jp.hisanet.charisafe.domain.model.IncidentStatus
import jp.hisanet.charisafe.domain.model.RecordingState
import jp.hisanet.charisafe.domain.model.VideoResolution
import jp.hisanet.charisafe.ui.component.EventMetricCard
import jp.hisanet.charisafe.ui.component.SegmentProgressBar
import jp.hisanet.charisafe.ui.model.EventUiConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

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
    val isRecording = uiState.status.state == RecordingState.RECORDING

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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (uiState.currentScreen) {
                    Screen.MAIN -> MainContent(viewModel, uiState)
                    Screen.HISTORY -> HistoryContent(uiState, historyListState)
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
            title = {
                Text(
                    "⚠️ 現在ライドのデータ削除",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "現在のライドデータ（動画・ログ・サマリー）をすべて削除して初期状態に戻しますか？",
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCurrentRide()
                        showDeleteConfirmDialog = false
                    },
                    enabled = !isRecording,
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
            title = {
                Text(
                    "📦 ライドデータを共有",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.shareAllData(); showShareModal = false },
                        enabled = !isRecording,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("すべて共有 (ZIP)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { viewModel.shareLogsOnly(); showShareModal = false },
                        enabled = !isRecording,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("ログのみ共有", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { viewModel.shareVideosOnly(); showShareModal = false },
                        enabled = !isRecording,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                    Text(
                        "動画およびログを圧縮しています...\nしばらくお待ちください。",
                        fontSize = 15.sp
                    )
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
    var isScreenLocked by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. 速度メーター (カード自体が誤操作防止ロックトグル)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isScreenLocked = !isScreenLocked },
                colors = CardDefaults.cardColors(
                    containerColor = if (isScreenLocked) Color(0xFFFF9800) else MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1f", uiState.status.currentSpeedKmh),
                            fontSize = 80.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isScreenLocked) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "km/h",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isScreenLocked) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }
            }

            SegmentProgressBar(
                isRecording = isRecording,
                sessionStartTimeMs = uiState.status.sessionStartTimeMs
            )
        }

        // 2. 感度スライダー 4種 ＆ リアルタイム比較バー (共通 EventMetricCard ループ)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                uiState.eventMetricCards.forEach { metric ->
                    EventMetricCard(
                        metric = metric,
                        onThresholdChanged = { newValue ->
                            if (!isScreenLocked) {
                                val updatedSensitivity = when (metric.eventType) {
                                    EventType.APPROACH -> uiState.sensitivity.copy(approachSensitivity = newValue)
                                    EventType.DECELERATION -> uiState.sensitivity.copy(
                                        decelerationSensitivity = newValue
                                    )

                                    EventType.COLLISION -> uiState.sensitivity.copy(collisionSensitivity = newValue)
                                    EventType.SOUND -> uiState.sensitivity.copy(soundSensitivity = newValue)
                                }
                                viewModel.updateSensitivity(updatedSensitivity)
                            }
                        }
                    )
                }
            }
        }

        // 3. シャッターボタン
        CameraShutterButton(isRecording = isRecording) {
            if (!isScreenLocked) {
                if (isRecording) viewModel.stopRecording() else viewModel.startRecording()
            }
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
            .aspectRatio(1f)
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
 * 📁 イベント履歴画面
 */
@Composable
fun HistoryContent(
    uiState: MainUiState,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    val context = LocalContext.current
    val grouped = uiState.groupedTimelineItems
    val highlightedEventId = uiState.targetEventIdToHighlight

    LaunchedEffect(highlightedEventId) {
        if (!highlightedEventId.isNullOrEmpty()) {
            var flatIndex = 0
            var found = false
            for ((_, items) in grouped) {
                flatIndex++ // 日付ヘッダー分
                for (item in items) {
                    if (item is TimelineItem.Recording) {
                        if (item.incidentLog.id == highlightedEventId || item.incidentLog.triggerDetectionLogId == highlightedEventId) {
                            found = true
                            break
                        }
                    }
                    flatIndex++
                }
                if (found) break
            }
            if (found) {
                try {
                    listState.animateScrollToItem(flatIndex)
                } catch (e: Exception) {
                    android.util.Log.e("HistoryContent", "Failed to scroll to highlighted item", e)
                }
            }
        }
    }

    if (grouped.isEmpty())
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("イベント履歴はありません", fontSize = 18.sp, color = Color.Gray)
        } else
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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
                        is TimelineItem.Recording -> "rec_${it.incidentLog.id}"
                        is TimelineItem.Detection -> "det_${it.detectionLog.id}"
                    }
                }) { item ->
                    if (item is TimelineItem.Recording) {
                        val incidentLog = item.incidentLog
                        var isExpanded by rememberSaveable { mutableStateOf(false) }
                        val associatedDetections =
                            uiState.eventDetectionLogs.filter { it.incidentId == incidentLog.id }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                val targetFile = resolveTargetFile(incidentLog)
                                                if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
                                                    try {
                                                        val uri = FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.fileprovider",
                                                            targetFile
                                                        )
                                                        val intent =
                                                            Intent(Intent.ACTION_VIEW).apply {
                                                                setDataAndType(uri, "video/mp4")
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                            }
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {
                                                        android.util.Log.e(
                                                            "HistoryContent",
                                                            "Failed to play video",
                                                            e
                                                        )
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "動画の再生に失敗しました: ${e.message}",
                                                            android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                } else {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "指定された動画ファイルが見つかりません（削除または移動済みの可能性があります）",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                    ) {
                                        val statusText =
                                            if (incidentLog.status == IncidentStatus.PENDING) "撮影中..." else "動画を再生"
                                        Text(
                                            text = "$statusText ${
                                                SimpleDateFormat(
                                                    "HH:mm:ss",
                                                    Locale.US
                                                ).format(Date(incidentLog.timestampMs))
                                            }",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (associatedDetections.isNotEmpty()) {
                                        TextButton(onClick = { isExpanded = !isExpanded }) {
                                            Text(
                                                text = if (isExpanded) "▲ 閉じる" else "▼ イベント(${associatedDetections.size})",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // 折りたたまれた子イベントリスト
                                if (isExpanded && associatedDetections.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        associatedDetections.forEach { detectionLog ->
                                            val triggerStr =
                                                if (detectionLog.id == incidentLog.triggerDetectionLogId) {
                                                    "★"
                                                } else {
                                                    ""
                                                }
                                            val timeStr =
                                                SimpleDateFormat("HH:mm:ss", Locale.US).format(
                                                    Date(detectionLog.timestampMs)
                                                )
                                            val uiDef = EventUiConfig.get(detectionLog.eventType)
                                            val valueStr =
                                                if (detectionLog.detectedValue > 0f || detectionLog.thresholdValue > 0f) {
                                                    EventUiConfig.formatMetricText(
                                                        detectionLog.eventType,
                                                        detectionLog.detectedValue,
                                                        detectionLog.thresholdValue
                                                    )
                                                } else detectionLog.displayLabel.ifEmpty {
                                                    "-"
                                                }
                                            val displayText =
                                                "$triggerStr${uiDef.emoji} ${uiDef.displayName}  $timeStr  |  $valueStr"

                                            Text(
                                                text = displayText,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.clickable {
                                                    try {
                                                        val uri =
                                                            "https://maps.google.com/?q=${detectionLog.latitude},${detectionLog.longitude}".toUri()
                                                        val intent =
                                                            Intent(Intent.ACTION_VIEW, uri).apply {
                                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                            }
                                                        context.startActivity(intent)
                                                    } catch (_: Exception) {
                                                    }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "走行時間: ${movingMin}分 ${movingSec}秒",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "停止時間: ${stoppedMin}分 ${stoppedSec}秒",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        text = "総距離: ${
                            String.format(
                                Locale.US,
                                "%.2f",
                                target.totalDistanceKm
                            )
                        } km", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )

                    Text(
                        text = "平均速度: ${
                            String.format(
                                Locale.US,
                                "%.1f",
                                target.averageSpeedKmh
                            )
                        } km/h", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                    Text(
                        text = "最高速度: ${
                            String.format(
                                Locale.US,
                                "%.1f",
                                target.maxSpeedKmh
                            )
                        } km/h", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Text(
                            text = "🚗 接近: ${target.eventCounts.approachCount}件 ${
                                String.format(
                                    Locale.US,
                                    "%.1f",
                                    target.approachPerHour
                                )
                            } 件/h",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Text(
                            text = "⚠ 減速: ${target.eventCounts.decelerationCount}件 ${
                                String.format(
                                    Locale.US,
                                    "%.1f",
                                    target.decelerationPerHour
                                )
                            } 件/h",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Text(
                            text = "💥 衝撃: ${target.eventCounts.collisionCount}件 ${
                                String.format(
                                    Locale.US,
                                    "%.1f",
                                    target.collisionPerHour
                                )
                            } 件/h",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Text(
                            text = "🔊 音量: ${target.eventCounts.soundCount}件 ${
                                String.format(
                                    Locale.US,
                                    "%.1f",
                                    target.soundPerHour
                                )
                            } 件/h",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val isRecording = uiState.status.state == RecordingState.RECORDING
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onShowShareModal,
                                enabled = !isRecording,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            ) {
                                Text(if (isRecording) "共有 (録画中は不可)" else "共有", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onShowDeleteDialog,
                                enabled = !isRecording,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text(if (isRecording) "削除 (録画中は不可)" else "削除", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    text = "⚠️ 録画中、設定変更をロックしています",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // 1. 撮影設定
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // アクティブカメラ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("アクティブカメラ", fontSize = 16.sp, color = Color.White)
                    var cameraExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { cameraExpanded = true },
                            enabled = !isRecording
                        ) {
                            Text(
                                if (config.activeCamera == ActiveCamera.REAR) "後方(リア)" else "前方(フロント)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = cameraExpanded,
                            onDismissRequest = { cameraExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("後方(リア)", fontSize = 16.sp) },
                                onClick = {
                                    viewModel.updateActiveCamera(ActiveCamera.REAR); cameraExpanded =
                                    false
                                })
                            DropdownMenuItem(
                                text = { Text("前方(フロント)", fontSize = 16.sp) },
                                onClick = {
                                    viewModel.updateActiveCamera(ActiveCamera.FRONT); cameraExpanded =
                                    false
                                })
                        }
                    }
                }

                // フレームレート (FPS)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("フレームレート (FPS)", fontSize = 16.sp, color = Color.White)
                    var fpsExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { fpsExpanded = true }, enabled = !isRecording) {
                            Text(
                                "${config.fps} fps",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = fpsExpanded,
                            onDismissRequest = { fpsExpanded = false }) {
                            listOf(5, 10, 15).forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("$f fps", fontSize = 16.sp) },
                                    onClick = { viewModel.updateFps(f); fpsExpanded = false })
                            }
                        }
                    }
                }

                // 録画解像度 (640x480, 1280x720, 1920x1080 選択可能)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("録画解像度", fontSize = 16.sp, color = Color.White)
                    var resExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { resExpanded = true }, enabled = !isRecording) {
                            Text(
                                "${config.resolution.width}x${config.resolution.height}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = resExpanded,
                            onDismissRequest = { resExpanded = false }) {
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
                // GPS記録間隔 (1秒を追加)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("GPS記録間隔", fontSize = 16.sp, color = Color.White)
                    var gpsExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { gpsExpanded = true }, enabled = !isRecording) {
                            Text(
                                "${config.gpsIntervalSeconds} 秒",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = gpsExpanded,
                            onDismissRequest = { gpsExpanded = false }) {
                            listOf(1L, 2L, 3L, 5L, 10L, 15L, 30L, 60L).forEach { sec ->
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

                // 接近検知 格子分割数 (8x8, 10x10, 20x20, 40x40 選択可能)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("接近検知 格子分割数", fontSize = 16.sp, color = Color.White)
                    var gridExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { gridExpanded = true }, enabled = !isRecording) {
                            Text(
                                "${config.approachGridCount}x${config.approachGridCount}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = gridExpanded,
                            onDismissRequest = { gridExpanded = false }
                        ) {
                            listOf(8, 10, 20, 40).forEach { cnt ->
                                DropdownMenuItem(
                                    text = { Text("${cnt}x${cnt}", fontSize = 16.sp) },
                                    onClick = {
                                        viewModel.updateApproachGridCount(cnt)
                                        gridExpanded = false
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
