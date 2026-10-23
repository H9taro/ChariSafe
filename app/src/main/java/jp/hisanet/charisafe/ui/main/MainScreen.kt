package jp.hisanet.charisafe.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import jp.hisanet.charisafe.domain.model.RecordingState
import jp.hisanet.charisafe.domain.model.VideoResolution
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    var isVideosExpanded by remember { mutableStateOf(false) }
    var isSummaryExpanded by remember { mutableStateOf(false) }
    var isGpxExpanded by remember { mutableStateOf(false) }
    var isSettingsExpanded by remember { mutableStateOf(false) }

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

    val shareCurrentLocation = {
        val lat = 35.681236
        val lon = 139.767125
        val mapsUrl = "https://maps.google.com/?q=$lat,$lon"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "「現在ChariSafeでサイクリング中です！」\n現在地: $mapsUrl (速度: ${uiState.status.currentSpeedKmh}km/h)")
        }
        context.startActivity(Intent.createChooser(intent, "現在地をGoogle Mapsで共有"))
    }

    var previousListSize by remember { mutableIntStateOf(uiState.savedEventFiles.size) }
    LaunchedEffect(uiState.savedEventFiles.size) {
        if (uiState.savedEventFiles.size > previousListSize) {
            Toast.makeText(context, "✨ イベント動画を保存しました！", Toast.LENGTH_LONG).show()
        }
        previousListSize = uiState.savedEventFiles.size
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3000L)
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

        if (!hasCameraAudioPermissions) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠️ カメラの権限が必要です",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "OS設定画面でカメラ権限を許可してください。", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
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

        // 1. 現在速度メーター
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "現在速度", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = String.format(Locale.US, "%.1f", uiState.status.currentSpeedKmh),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(text = "km/h", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
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

        // 3. イベント感度スライダー
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "⚙️ イベント感度調整 (常時変更可能)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Text(text = "急減速閾値: ${uiState.sensitivity.decelerationSensitivity.toInt()} km/h", fontSize = 11.sp)
                Slider(
                    value = uiState.sensitivity.decelerationSensitivity,
                    onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(decelerationSensitivity = it)) },
                    valueRange = 10f..25f,
                    steps = 15
                )

                Text(text = "接近検知感度: ${(uiState.sensitivity.approachSensitivity * 100).toInt()}%", fontSize = 11.sp)
                Slider(
                    value = uiState.sensitivity.approachSensitivity,
                    onValueChange = { viewModel.updateSensitivity(uiState.sensitivity.copy(approachSensitivity = it)) },
                    valueRange = 0.3f..1.0f,
                    steps = 7
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. 構造化イベント件数サマリーカード
        Card(
            modifier = Modifier.fillMaxWidth().clickable { isHistoryExpanded = !isHistoryExpanded },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "📊 イベント件数サマリー (タップで履歴)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    EventCountBadge("⚠ 急減速", uiState.eventCounts.decelerationCount)
                    EventCountBadge("🚗 接近", uiState.eventCounts.approachCount)
                    EventCountBadge("💥 衝突", uiState.eventCounts.collisionCount)
                    EventCountBadge("🔊 音", uiState.eventCounts.soundCount)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 5. 現在地共有ボタン
        Button(
            onClick = { shareCurrentLocation() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("📍 現在地をGoogle Mapsで共有する", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 【折りたたみエリア (Accordion)】
        // ==========================================

        AccordionSection(title = "📁 イベント履歴 (${uiState.eventLogs.size}件)", expanded = isHistoryExpanded, onToggle = { isHistoryExpanded = !isHistoryExpanded }) {
            if (uiState.eventLogs.isEmpty()) {
                Text("イベント履歴はありません", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                uiState.eventLogs.forEach { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = "${log.eventType.emoji} ${log.eventType.displayName}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = "時刻: ${SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date(log.timestampMs))}", fontSize = 11.sp, color = Color.Gray)
                            Text(text = "速度: ${log.speedKmh} km/h", fontSize = 11.sp)
                            Text(text = "感度設定 ➔ 接近: ${(log.approachSensitivity * 100).toInt()}%, 減速: ${log.decelerationSensitivity.toInt()}km/h", fontSize = 10.sp, color = Color.DarkGray)

                            if (!log.videoFilePath.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = { shareFile(File(log.videoFilePath)) },
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("▶ 動画再生・共有", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        AccordionSection(title = "🎥 保存済み動画 (${uiState.savedEventFiles.size}件)", expanded = isVideosExpanded, onToggle = { isVideosExpanded = !isVideosExpanded }) {
            if (uiState.savedEventFiles.isEmpty()) {
                Text("保存された動画はありません", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                uiState.savedEventFiles.forEach { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = file.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { shareFile(file) }, modifier = Modifier.height(32.dp)) {
                            Text("共有", fontSize = 11.sp)
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
                    Text(text = "⚠ 急減速: ${summary.eventCounts.decelerationCount}件 (${String.format(Locale.US, "%.2f", summary.decelerationPerKm)}件/km | ${String.format(Locale.US, "%.1f", summary.decelerationPerHour)}件/h)", fontSize = 11.sp)
                }
            }
        }

        AccordionSection(title = "🗺️ GPXログ情報", expanded = isGpxExpanded, onToggle = { isGpxExpanded = !isGpxExpanded }) {
            Text(text = uiState.status.activeGpxFile ?: "GPXログ未取得", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
        }

        val isRecording = uiState.status.state == RecordingState.RECORDING
        val isExporting = uiState.status.state == RecordingState.EXPORTING

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
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 録画スタート / ストップ ボタン
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isRecording && !isExporting) {
                Button(
                    onClick = {
                        hasCameraAudioPermissions = checkCameraAudioPermissions(context)
                        if (hasCameraAudioPermissions) viewModel.startRecording() else openAppSettings()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("録画スタート", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else if (isExporting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text("イベント動画を即時保存中...", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { viewModel.stopRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("録画ストップ (サマリー生成)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EventCountBadge(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(text = "$count 件", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
