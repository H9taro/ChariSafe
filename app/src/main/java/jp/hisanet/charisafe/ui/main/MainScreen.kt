package jp.hisanet.charisafe.ui.main

import android.content.Intent
import android.net.Uri
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

    // 画面内でのハイライト状態（2秒後に解除）
    var highlightedEventId by remember { mutableStateOf<String?>(null) }
    val historyListState = rememberLazyListState()

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
                    fontSize = 18.sp
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("🏠 ホーム", fontSize = 16.sp) },
                    selected = uiState.currentScreen == Screen.MAIN,
                    onClick = {
                        viewModel.setScreen(Screen.MAIN)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("📁 イベント履歴", fontSize = 16.sp) },
                    selected = uiState.currentScreen == Screen.HISTORY,
                    onClick = {
                        viewModel.setScreen(Screen.HISTORY)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("📈 ライド結果", fontSize = 16.sp) },
                    selected = uiState.currentScreen == Screen.RIDE_RESULTS,
                    onClick = {
                        viewModel.setScreen(Screen.RIDE_RESULTS)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("⚙️ 設定", fontSize = 16.sp) },
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
                        Text(title, fontWeight = FontWeight.Bold)
                    },
                    navigationIcon = {
                        if (uiState.currentScreen == Screen.MAIN) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Text(text = "☰", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            IconButton(onClick = { viewModel.setScreen(Screen.MAIN) }) {
                                Text(text = "←", fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
                    Screen.RIDE_RESULTS -> RideResultContent(viewModel, uiState)
                    Screen.SETTINGS -> SettingsContent(viewModel, uiState)
                }
            }
        }
    }
}

/**
 * 🏠 ホーム画面（スライダー文字さらに大きく・白色件数・赤色ボタン）
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

        // 2. 感度スライダー 4種 (順序固定: 接近 ➔ 減速 ➔ 衝撃 ➔ 音量)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 接近
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "接近 ${(uiState.sensitivity.approachSensitivity * 100).toInt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "${uiState.sessionEventCounts.approachCount}件", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Slider(
                    value = uiState.sensitivity.approachSensitivity,
                    onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(approachSensitivity = it)) },
                    valueRange = 0.3f..1.0f,
                    steps = 7
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 減速
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "減速 ${String.format(Locale.US, "%.1f", uiState.sensitivity.decelerationSensitivity)}秒", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "${uiState.sessionEventCounts.decelerationCount}件", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Slider(
                    value = uiState.sensitivity.decelerationSensitivity,
                    onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(decelerationSensitivity = it)) },
                    valueRange = 0.5f..3.0f,
                    steps = 25
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 衝撃
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "衝撃 ${String.format(Locale.US, "%.1f", uiState.sensitivity.collisionSensitivity)}G", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "${uiState.sessionEventCounts.collisionCount}件", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Slider(
                    value = uiState.sensitivity.collisionSensitivity,
                    onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(collisionSensitivity = it)) },
                    valueRange = 2.0f..5.0f,
                    steps = 15
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 音量
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "音量 ${uiState.sensitivity.soundSensitivity.toInt()}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = "${uiState.sessionEventCounts.soundCount}件", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Slider(
                    value = uiState.sensitivity.soundSensitivity,
                    onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(soundSensitivity = it)) },
                    valueRange = 1000f..10000f,
                    steps = 18
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. 赤色の録画開始ボタン
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = if (isRecording) Color(0xFF424242) else Color(0xFFC62828),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (isRecording) viewModel.stopRecording() else viewModel.startRecording()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier.size(if (isRecording) 32.dp else 40.dp).background(
                        color = Color.White,
                        shape = if (isRecording) RoundedCornerShape(4.dp) else CircleShape
                    )
                )
            }
        }
    }
}

/**
 * 📁 イベント履歴画面（完全1行・行タップMap共有・再生/共有）
 */
@Composable
fun HistoryContent(uiState: MainUiState, highlightedEventId: String?, listState: androidx.compose.foundation.lazy.LazyListState) {
    val context = LocalContext.current
    val grouped = uiState.groupedTimelineItems

    if (grouped.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("イベント履歴はありません", fontSize = 16.sp, color = Color.Gray)
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
                        fontSize = 17.sp,
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
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🎥 記録  ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(inc.timestampMs))}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        // ▶ 再生ボタン
                                        OutlinedButton(
                                            onClick = {
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
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                        ) {
                                            Text("▶ 再生", fontSize = 11.sp)
                                        }

                                        // 📤 共有ボタン
                                        OutlinedButton(
                                            onClick = {
                                                val targetFile = resolveTargetFile(context, inc)
                                                if (targetFile != null) {
                                                    try {
                                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "video/mp4"
                                                            putExtra(Intent.EXTRA_STREAM, uri)
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        val chooser = Intent.createChooser(intent, "動画を共有").apply {
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(chooser)
                                                    } catch (e: Exception) {
                                                        android.util.Log.e("HistoryContent", "Failed to share video", e)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                        ) {
                                            Text("📤 共有", fontSize = 11.sp)
                                        }
                                    }
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
                                        fontSize = 13.sp,
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
fun RideResultContent(viewModel: MainViewModel, uiState: MainUiState) {
    val summaries = uiState.rideSummaries
    var selectedSummary by remember(summaries) { mutableStateOf(summaries.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }

    if (summaries.isEmpty() && uiState.currentRideSummary == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("完了したライド履歴はありません", fontSize = 16.sp, color = Color.Gray)
        }
    } else {
        val target = selectedSummary ?: uiState.currentRideSummary
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (summaries.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        val dateText = if (target != null) {
                            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(target.startTimeMs))
                        } else "ライドを選択"
                        Text("走行選択: $dateText ▼", fontSize = 16.sp)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        summaries.forEach { sum ->
                            DropdownMenuItem(
                                text = {
                                    val dt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(sum.startTimeMs))
                                    Text("$dt 走行 (${String.format(Locale.US, "%.1f", sum.totalDistanceKm)}km)", fontSize = 15.sp)
                                },
                                onClick = {
                                    selectedSummary = sum
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (target != null) {
                val movingMin = target.movingTimeSec / 60
                val movingSec = target.movingTimeSec % 60
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = "走行時間: ${movingMin}分 ${movingSec}秒 (停止: ${target.stoppedTimeSec}秒)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(text = "総距離: ${String.format(Locale.US, "%.2f", target.totalDistanceKm)} km", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(text = "平均速度: ${String.format(Locale.US, "%.1f", target.averageSpeedKmh)} km/h (最高: ${target.maxSpeedKmh} km/h)", fontSize = 15.sp)

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "【危険度定量的指標】", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                        
                        // 順序統一: 接近 ➔ 減速 ➔ 衝撃 ➔ 音量 (完全に行分離)
                        // 1. 接近
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(text = "🚗 接近: ${target.eventCounts.approachCount}件", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(text = "   • 距離あたり: ${String.format(Locale.US, "%.2f", target.approachPerKm)} 件/km", fontSize = 13.sp, color = Color.Gray)
                            Text(text = "   • 時間あたり: ${String.format(Locale.US, "%.1f", target.approachPerHour)} 件/h", fontSize = 13.sp, color = Color.Gray)
                        }
                        // 2. 減速
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(text = "⚠ 減速: ${target.eventCounts.decelerationCount}件", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(text = "   • 距離あたり: ${String.format(Locale.US, "%.2f", target.decelerationPerKm)} 件/km", fontSize = 13.sp, color = Color.Gray)
                            Text(text = "   • 時間あたり: ${String.format(Locale.US, "%.1f", target.decelerationPerHour)} 件/h", fontSize = 13.sp, color = Color.Gray)
                        }
                        // 3. 衝撃
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(text = "💥 衝撃: ${target.eventCounts.collisionCount}件", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(text = "   • 距離あたり: ${String.format(Locale.US, "%.2f", target.collisionPerKm)} 件/km", fontSize = 13.sp, color = Color.Gray)
                            Text(text = "   • 時間あたり: ${String.format(Locale.US, "%.1f", target.collisionPerHour)} 件/h", fontSize = 13.sp, color = Color.Gray)
                        }
                        // 4. 音量
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(text = "🔊 音量: ${target.eventCounts.soundCount}件", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(text = "   • 距離あたり: ${String.format(Locale.US, "%.2f", target.soundPerKm)} 件/km", fontSize = 13.sp, color = Color.Gray)
                            Text(text = "   • 時間あたり: ${String.format(Locale.US, "%.1f", target.soundPerHour)} 件/h", fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { viewModel.shareGpxFile() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("🗺️ GPXログを共有", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { target?.let { viewModel.deleteRideSummary(it) } },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("🗑️ このライド結果を削除", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * ⚙️ 設定画面（録画中は設定変更をロック）
 */
@Composable
fun SettingsContent(viewModel: MainViewModel, uiState: MainUiState) {
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
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "撮影設定 (Camera & Resolution)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                
                // アクティブカメラ
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("アクティブカメラ", fontSize = 14.sp, color = Color.White)
                    var cameraExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { cameraExpanded = true }, enabled = !isRecording) {
                            Text(if (config.activeCamera == ActiveCamera.REAR) "後方(リア)" else "前方(フロント)")
                        }
                        DropdownMenu(expanded = cameraExpanded, onDismissRequest = { cameraExpanded = false }) {
                            DropdownMenuItem(text = { Text("後方(リア)") }, onClick = { viewModel.updateActiveCamera(ActiveCamera.REAR); cameraExpanded = false })
                            DropdownMenuItem(text = { Text("前方(フロント)") }, onClick = { viewModel.updateActiveCamera(ActiveCamera.FRONT); cameraExpanded = false })
                        }
                    }
                }

                // フレームレート (FPS)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("フレームレート (FPS)", fontSize = 14.sp, color = Color.White)
                    var fpsExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { fpsExpanded = true }, enabled = !isRecording) {
                            Text("${config.fps} fps")
                        }
                        DropdownMenu(expanded = fpsExpanded, onDismissRequest = { fpsExpanded = false }) {
                            listOf(5, 10, 15).forEach { f ->
                                DropdownMenuItem(text = { Text("$f fps") }, onClick = { viewModel.updateFps(f); fpsExpanded = false })
                            }
                        }
                    }
                }

                // 録画解像度
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("録画解像度", fontSize = 14.sp, color = Color.White)
                    var resExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { resExpanded = true }, enabled = !isRecording) {
                            Text("${config.resolution.width}x${config.resolution.height}")
                        }
                        DropdownMenu(expanded = resExpanded, onDismissRequest = { resExpanded = false }) {
                            VideoResolution.entries.forEach { res ->
                                DropdownMenuItem(text = { Text("${res.width}x${res.height}") }, onClick = { resExpanded = false })
                            }
                        }
                    }
                }

                HorizontalDivider()

                // リングバッファ時間
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("リングバッファ保持時間", fontSize = 14.sp, color = Color.White)
                    var rbExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { rbExpanded = true }, enabled = !isRecording) {
                            Text("${config.ringBufferMinutes} 分")
                        }
                        DropdownMenu(expanded = rbExpanded, onDismissRequest = { rbExpanded = false }) {
                            listOf(3, 5, 10).forEach { min ->
                                DropdownMenuItem(text = { Text("$min 分") }, onClick = { viewModel.updateRingBuffer(min); rbExpanded = false })
                            }
                        }
                    }
                }

                // GPS記録間隔
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("GPS記録間隔", fontSize = 14.sp, color = Color.White)
                    var gpsExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { gpsExpanded = true }, enabled = !isRecording) {
                            Text("${config.gpsIntervalSeconds} 秒")
                        }
                        DropdownMenu(expanded = gpsExpanded, onDismissRequest = { gpsExpanded = false }) {
                            listOf(5L, 10L, 30L, 60L).forEach { sec ->
                                DropdownMenuItem(text = { Text("$sec 秒") }, onClick = { viewModel.updateGpsInterval(sec); gpsExpanded = false })
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "ストレージ & 起動時動作", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                
                // 起動時クリア
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("起動時に過去のログ・動画をクリア", fontSize = 14.sp, color = Color.White)
                    }
                    Switch(
                        checked = config.clearPreviousLogsAndVideosOnStart,
                        onCheckedChange = { viewModel.updateClearPreviousData(it) },
                        enabled = !isRecording
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "CSVデバッグ出力設定", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                
                // 並び順完全統一: 接近 ➔ 減速 ➔ 衝撃 ➔ 音量
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("接近検知 CSV出力", fontSize = 14.sp, color = Color.White)
                    Switch(checked = config.approachCsvEnabled, onCheckedChange = { viewModel.updateApproachCsvEnabled(it) }, enabled = !isRecording)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("減速検知 CSV出力", fontSize = 14.sp, color = Color.White)
                    Switch(checked = config.decelerationCsvEnabled, onCheckedChange = { viewModel.updateDecelerationCsvEnabled(it) }, enabled = !isRecording)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("衝撃検知 CSV出力", fontSize = 14.sp, color = Color.White)
                    Switch(checked = config.collisionCsvEnabled, onCheckedChange = { viewModel.updateCollisionCsvEnabled(it) }, enabled = !isRecording)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("音量検知 CSV出力", fontSize = 14.sp, color = Color.White)
                    Switch(checked = config.audioCsvEnabled, onCheckedChange = { viewModel.updateAudioCsvEnabled(it) }, enabled = !isRecording)
                }

                Spacer(modifier = Modifier.height(6.dp))
                // デバッグボタンの縦行分離
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.shareDebugLogs() },
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text("📤 デバッグログ共有(ZIP)", fontSize = 13.sp)
                    }
                    Button(
                        onClick = { viewModel.deleteDebugLogs() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text("🗑️ ログ全削除", fontSize = 13.sp)
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
