package jp.hisanet.charisafe.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import jp.hisanet.charisafe.domain.model.RecordingState
import java.io.File
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

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraAudioPermissions = checkCameraAudioPermissions(context)
                hasLocationPermission = checkLocationPermission(context)
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

    val shareVideoFile: (File) -> Unit = { file ->
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "イベント動画を共有 (Google Files等)"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "共有エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 新規保存時のToast通知
    var previousListSize by remember { mutableIntStateOf(uiState.savedEventFiles.size) }
    LaunchedEffect(uiState.savedEventFiles.size) {
        if (uiState.savedEventFiles.size > previousListSize) {
            Toast.makeText(context, "✨ イベント動画を即時保存しました！", Toast.LENGTH_LONG).show()
        }
        previousListSize = uiState.savedEventFiles.size
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
            text = "ChariSafe：自転車用レコーダー",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

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
                    Text(
                        text = "録画を行うために、OS設定画面でカメラ権限を許可してください。",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { requestPermissions() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("権限要求", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { openAppSettings() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("設定画面を開く", fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Current Speed Meter Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "速度", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = String.format(Locale.US, "%.1f", uiState.status.currentSpeedKmh),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(text = "km/h", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // System Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val isRecording = uiState.status.state == RecordingState.RECORDING
                val isExporting = uiState.status.state == RecordingState.EXPORTING

                RowStatusItem(
                    label = "ステータス",
                    value = when (uiState.status.state) {
                        RecordingState.RECORDING -> "🔴 リングバッファ録画中"
                        RecordingState.EXPORTING -> "⚡ 過去ログを即時結合中..."
                        RecordingState.PREPARING -> "⏳ 準備中..."
                        RecordingState.SLEEP -> "🌙 スリープ中"
                        RecordingState.ERROR -> "❌ エラー"
                        else -> "⚪ 停止中 (IDLE)"
                    },
                    color = if (isRecording) Color.Red else if (isExporting) Color(0xFFFF9800) else Color.Gray
                )
                if (hasLocationPermission && uiState.status.activeGpxFile != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "GPXログ: 有効 (30秒間隔保存中)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                } else if (!hasLocationPermission) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "GPXログ: 未許可 (位置情報権限オフ)", fontSize = 12.sp, color = Color.Gray)
                }


                RowStatusItem(label = "リングバッファ時間", value = "${uiState.config.ringBufferMinutes} 分")
                RowStatusItem(label = "GPS情報更新間隔", value = "${uiState.config.gpsIntervalSeconds} 秒")

                if (uiState.status.errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "エラー: ${uiState.status.errorMessage}", color = Color.Red, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Controls / Action Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val isRecording = uiState.status.state == RecordingState.RECORDING
            val isExporting = uiState.status.state == RecordingState.EXPORTING

            if (!isRecording && !isExporting) {
                Button(
                    onClick = {
                        hasCameraAudioPermissions = checkCameraAudioPermissions(context)
                        if (hasCameraAudioPermissions) {
                            viewModel.startRecording()
                        } else {
                            openAppSettings()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("録画スタート", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else if (isExporting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text("イベント動画を即時保存中...", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { viewModel.stopRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("録画ストップ", fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Event Save Button (前5分 -> 即時保存に改良)
                Button(
                    onClick = { viewModel.triggerManualEvent() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("⚠️ イベント保存", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Saved Event Files List (一覧表示 ＋ タップして選択・共有)
            if (uiState.savedEventFiles.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "項目をタップすると共有・削除カードが開きます",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        uiState.savedEventFiles.forEach { file ->
                            val isSelected = uiState.selectedEventFile == file
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        viewModel.selectEventFile(if (isSelected) null else file)
                                    }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = file.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    // 選択されている場合のみ詳細アクション（共有・削除）を表示
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { shareVideoFile(file) },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                                modifier = Modifier.weight(1f).height(36.dp)
                                            ) {
                                                Text("📤 共有 (Google Files等)", fontSize = 12.sp)
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.removeEventFile(file) },
                                                modifier = Modifier.weight(0.5f).height(36.dp)
                                            ) {
                                                Text("🗑️ 削除", fontSize = 12.sp)
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
    }
}

@Composable
private fun RowStatusItem(label: String, value: String, color: Color = Color.Unspecified) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text(text = value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
    }
}

private fun checkCameraAudioPermissions(context: android.content.Context): Boolean {
    val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    return camera
}

private fun checkLocationPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
