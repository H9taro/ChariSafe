package jp.hisanet.charisafe.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import jp.hisanet.charisafe.domain.model.ActiveCamera
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.RecordingState
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraAudioPermissions by remember { mutableStateOf(checkCameraAudioPermissions(context)) }
    var hasLocationPermission by remember { mutableStateOf(checkLocationPermission(context)) }

    // アコーディオン開閉状態
    var isHistoryExpanded by remember { mutableStateOf(false) }
    var isSummaryExpanded by remember { mutableStateOf(false) }
    var isSettingsExpanded by remember { mutableStateOf(false) }

    // イベント履歴のフィルター状態 (null = すべて, または各EventType)
    var selectedFilterType by remember { mutableStateOf<EventType?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraAudioPermissions = checkCameraAudioPermissions(context)
                hasLocationPermission = checkLocationPermission(context)
                viewModel.loadExistingEvents()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val requestPermissions = {
        val activity = context as? android.app.Activity
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (activity != null) {
            androidx.core.app.ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), 1001)
        }
    }

    val openAppSettings = {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    val shareFile: (File) -> Unit = { file ->
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mimeType = if (file.name.endsWith(".jpg")) "image/jpeg" else "video/mp4"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "ファイル共有 (Google Files等)"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "共有エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val shareMapLocation: (Double, Double) -> Unit = { lat, lon ->
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(ChariSafe+Event)")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon"))
            context.startActivity(browserIntent)
        }
    }

    val shareRoute = {
        val lat = 35.681236
        val lon = 139.767125
        val mapsUrl = "https://maps.google.com/?q=$lat,$lon"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "「現在ChariSafeで走行中です！」\n現在地: $mapsUrl")
        }
        context.startActivity(Intent.createChooser(intent, "走行経路を共有"))
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3000L.milliseconds)
            viewModel.loadExistingEvents()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraAudioPermissions || !hasLocationPermission) {
            requestPermissions()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "ChariSafe ライドモニター",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (!hasCameraAudioPermissions || !hasLocationPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠️ カメラまたは位置情報の権限が必要です",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "OS設定画面で必要な権限を許可してください。", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { requestPermissions() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("権限要求", fontSize = 13.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ==========================================
        // 【常時表示エリア】
        // ==========================================

        // 1. 現在速度 (タイトル「現在速度」を廃止)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f km/h", uiState.status.currentSpeedKmh),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. 最後のイベントカード
        if (uiState.lastEvent != null) {
            val last = uiState.lastEvent!!
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "🚨 最後のイベント検知", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${last.eventType.emoji} ${last.eventType.displayName} (速度: ${last.speedKmh}km/h)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 3. イベント感度スライダー (タイトル「イベント感度調整」を廃止)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "急減速閾値: ${uiState.sensitivity.decelerationSensitivity.toInt()} km/h", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Slider(
                    value = uiState.sensitivity.decelerationSensitivity,
                    onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(decelerationSensitivity = it)) },
                    valueRange = 10f..25f,
                    steps = 15
                )

                Text(text = "接近検知感度: ${(uiState.sensitivity.approachSensitivity * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Slider(
                    value = uiState.sensitivity.approachSensitivity,
                    onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(approachSensitivity = it)) },
                    valueRange = 0.3f..1.0f,
                    steps = 7
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val isRecording = uiState.status.state == RecordingState.RECORDING
        val isExporting = uiState.status.state == RecordingState.EXPORTING

        // 4. 【厳格配置】イベント感度調整とイベント件数サマリの間に配置された 録画スタート / 録画ストップ ボタン (シャッターボタン形状)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isRecording && !isExporting) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            hasCameraAudioPermissions = checkCameraAudioPermissions(context)
                            if (hasCameraAudioPermissions) viewModel.startRecording() else openAppSettings()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("録画スタート", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            } else if (isExporting) {
                CircularProgressIndicator()
                Text("イベント保存中...", fontSize = 12.sp)
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .clickable { viewModel.stopRecording() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.White)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("録画ストップ", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. イベント履歴グループ (フィルタ付き)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📊 イベント履歴", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (selectedFilterType != null) {
                        OutlinedButton(onClick = { selectedFilterType = null }, modifier = Modifier.height(28.dp)) {
                            Text("解除", fontSize = 10.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    EventFilterBadge("全(${uiState.eventLogs.size})", isSelected = selectedFilterType == null) { selectedFilterType = null }
                    EventFilterBadge("⚠(${uiState.eventCounts.decelerationCount})", isSelected = selectedFilterType == EventType.DECELERATION) { selectedFilterType = EventType.DECELERATION }
                    EventFilterBadge("🚗(${uiState.eventCounts.approachCount})", isSelected = selectedFilterType == EventType.APPROACH) { selectedFilterType = EventType.APPROACH }
                    EventFilterBadge("💥(${uiState.eventCounts.collisionCount})", isSelected = selectedFilterType == EventType.COLLISION) { selectedFilterType = EventType.COLLISION }
                    EventFilterBadge("🔊(${uiState.eventCounts.soundCount})", isSelected = selectedFilterType == EventType.SOUND) { selectedFilterType = EventType.SOUND }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ==========================================
        // 【折りたたみエリア (Accordion)】
        // ==========================================

        val filteredLogs = if (selectedFilterType == null) uiState.eventLogs else uiState.eventLogs.filter { it.eventType == selectedFilterType }

        AccordionSection(title = "📁 イベント履歴 & 動画一覧 (${filteredLogs.size}件)", expanded = isHistoryExpanded, onToggle = { isHistoryExpanded = !isHistoryExpanded }) {
            if (filteredLogs.isEmpty()) {
                Text("該当するイベント履歴はありません", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                filteredLogs.forEach { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = "${log.eventType.emoji} ${log.eventType.displayName}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = "時刻: ${SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date(log.timestampMs))}", fontSize = 11.sp, color = Color.Gray)
                            Text(text = "速度: ${log.speedKmh} km/h | 接近感度: ${(log.approachSensitivity * 100).toInt()}%", fontSize = 11.sp)

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!log.videoFilePath.isNullOrEmpty()) {
                                    OutlinedButton(
                                        onClick = { shareFile(File(log.videoFilePath)) },
                                        modifier = Modifier.weight(1f).height(36.dp)
                                    ) {
                                        Text("▶ 動画再生・共有", fontSize = 11.sp)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { shareMapLocation(log.latitude, log.longitude) },
                                    modifier = Modifier.weight(1f).height(36.dp)
                                ) {
                                    Text("📍 Map共有", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        AccordionSection(title = "📈 ライドサマリー & 速度分析", expanded = isSummaryExpanded, onToggle = { isSummaryExpanded = !isSummaryExpanded }) {
            val summary = uiState.currentRideSummary
            if (summary == null) {
                Text("録画を終了するとライドサマリーが生成されます", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(text = "総距離: ${String.format(Locale.US, "%.2f", summary.totalDistanceKm)} km", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(text = "平均速度: ${String.format(Locale.US, "%.1f", summary.averageSpeedKmh)} km/h (最高: ${summary.maxSpeedKmh} km/h)", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "【危険度定量的指標】", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text(text = "🚗 接近検知: ${summary.eventCounts.approachCount}件 (${String.format(Locale.US, "%.2f", summary.approachPerKm)}件/km | ${String.format(Locale.US, "%.1f", summary.approachPerHour)}件/h)", fontSize = 11.sp)
                    Text(text = "⚠ 急減速: ${summary.eventCounts.decelerationCount}件 (${String.format(Locale.US, "%.2f", summary.decelerationPerKm)}件/km | ${String.format(Locale.US, "%.2f", summary.decelerationPerHour)}件/h)", fontSize = 11.sp)
                }
            }
        }

        AccordionSection(title = "⚙️ 録画設定 (停止中のみ変更可)", expanded = isSettingsExpanded, onToggle = { isSettingsExpanded = !isSettingsExpanded }) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (isRecording) {
                    Text(text = "※録画中は設定変更できません", fontSize = 11.sp, color = Color.Red)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                DropdownSettingItem(
                    label = "撮影カメラ",
                    currentValue = if (uiState.config.activeCamera == ActiveCamera.REAR) "後方 (リア)" else "前方 (フロント)",
                    options = ActiveCamera.entries,
                    optionLabel = { if (it == ActiveCamera.REAR) "後方 (リア)" else "前方 (フロント)" },
                    enabled = !isRecording,
                    onSelected = { viewModel.updateActiveCamera(it) }
                )

                DropdownSettingItem(
                    label = "リングバッファ時間",
                    currentValue = "${uiState.config.ringBufferMinutes} 分",
                    options = listOf(3, 5, 10),
                    optionLabel = { "$it 分" },
                    enabled = !isRecording,
                    onSelected = { viewModel.updateRingBuffer(it) }
                )

                DropdownSettingItem(
                    label = "GPS更新間隔",
                    currentValue = "${uiState.config.gpsIntervalSeconds} 秒",
                    options = listOf(1L, 5L, 10L, 30L),
                    optionLabel = { "$it 秒" },
                    enabled = !isRecording,
                    onSelected = { viewModel.updateGpsInterval(it) }
                )

                DropdownSettingItem(
                    label = "撮影 FPS",
                    currentValue = "${uiState.config.fps} fps",
                    options = listOf(5, 10, 15),
                    optionLabel = { "$it fps" },
                    enabled = !isRecording,
                    onSelected = { viewModel.updateFps(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.updateClearPreviousData(!uiState.config.clearPreviousLogsAndVideosOnStart)
                    }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "起動時に過去のログ・動画をクリア", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(text = "（問題切り分け用・デフォルトOFF）", fontSize = 10.sp, color = Color.Gray)
                    }
                    Checkbox(
                        checked = uiState.config.clearPreviousLogsAndVideosOnStart,
                        onCheckedChange = { viewModel.updateClearPreviousData(it) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { shareRoute() },
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                ) {
                    Text("🌐 走行経路を共有 (GPX)", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun EventFilterBadge(label: String, isSelected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
        ),
        modifier = Modifier.height(32.dp).padding(horizontal = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
fun AccordionSection(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(text = if (expanded) "▲" else "▼", fontSize = 12.sp)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun <T> DropdownSettingItem(
    label: String,
    currentValue: String,
    options: List<T>,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium)

        Box {
            OutlinedButton(
                onClick = { if (enabled) expanded = true },
                enabled = enabled,
                modifier = Modifier.height(32.dp)
            ) {
                Text(text = currentValue, fontSize = 11.sp)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text = optionLabel(option), fontSize = 12.sp) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun checkCameraAudioPermissions(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

private fun checkLocationPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
