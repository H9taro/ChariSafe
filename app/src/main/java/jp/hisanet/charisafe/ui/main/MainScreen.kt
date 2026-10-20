package jp.hisanet.charisafe.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import jp.hisanet.charisafe.domain.model.RecordingState
import java.util.Locale

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var hasPermissions by remember { mutableStateOf(checkPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            val permissions = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
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
            text = "ChariSafe MVP",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "自転車向けセーフティレコーダー (${uiState.config.resolution.label} / ${uiState.config.fps}fps / H.265)",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

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
                Text(text = "現在速度", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
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
                        RecordingState.RECORDING -> "🔴 5分リングバッファ録画中"
                        RecordingState.EXPORTING -> "⏳ イベント保存中 (前5分+後1分)..."
                        RecordingState.PREPARING -> "⏳ 準備中..."
                        RecordingState.SLEEP -> "🌙 スリープ中"
                        RecordingState.ERROR -> "❌ エラー"
                        else -> "⚪ 停止中 (IDLE)"
                    },
                    color = if (isRecording) Color.Red else if (isExporting) Color(0xFFFF9800) else Color.Gray
                )

                RowStatusItem(label = "リングバッファ時間", value = "${uiState.config.ringBufferMinutes} 分")
                RowStatusItem(label = "GPS情報更新間隔", value = "${uiState.config.gpsIntervalSeconds} 秒")

                if (uiState.status.activeGpxFile != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "GPXログ: 有効 (30秒間隔保存中)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }

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
                    onClick = { viewModel.startRecording() },
                    enabled = hasPermissions,
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
                    Text("イベント動画を出力中...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                // Event Save Button (前5分 + 後1分)
                Button(
                    onClick = { viewModel.triggerManualEvent() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("⚠️ イベント保存 (前5分 ＋ 後1分)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.stopRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("録画ストップ", fontSize = 16.sp)
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

private fun checkPermissions(context: android.content.Context): Boolean {
    val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val notification = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return camera && audio && fineLocation && notification
}
