package jp.hisanet.charisafe

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import jp.hisanet.charisafe.service.RecordingService
import jp.hisanet.charisafe.ui.theme.ChariSafeTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var recordingService: RecordingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RecordingService.LocalBinder
            recordingService = binder?.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val intent = Intent(this, RecordingService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)

        setContent {
            ChariSafeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Poc1MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        getServiceState = { recordingService?.serviceState },
                        onStartRecording = { selectedFps ->
                            val startIntent = Intent(this, RecordingService::class.java).apply {
                                action = RecordingService.ACTION_START_RECORDING
                                putExtra(RecordingService.EXTRA_TARGET_FPS, selectedFps)
                            }
                            startForegroundService(startIntent)
                        },
                        onStopRecording = {
                            val stopIntent = Intent(this, RecordingService::class.java).apply {
                                action = RecordingService.ACTION_STOP_RECORDING
                            }
                            startService(stopIntent)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun Poc1MainScreen(
    modifier: Modifier = Modifier,
    getServiceState: () -> kotlinx.coroutines.flow.StateFlow<RecordingService.ServiceState>?,
    onStartRecording: (Int) -> Unit,
    onStopRecording: () -> Unit
) {
    val context = LocalContext.current
    var selectedFps by remember { mutableStateOf(15) }
    var hasPermissions by remember { mutableStateOf(checkPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            val permissionsToRequest = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    val serviceStateFlow = getServiceState()
    val serviceState by serviceStateFlow?.collectAsState() ?: remember {
        mutableStateOf(RecordingService.ServiceState())
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
            text = "ChariSafe PoC-1 検証",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "720p / 15fps / H.265 画面ロック連続安定性テスト",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!hasPermissions) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "カメラ、マイク、通知のパーミッションが必要です。",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                RowStatusItem(
                    label = "録画ステータス",
                    value = if (serviceState.isRecording) "🔴 録画中 (${serviceState.targetFps}fps)" else "⚪ 停止中 (STOPPED)",
                    color = if (serviceState.isRecording) Color.Red else Color.Gray
                )

                val elapsedMin = serviceState.elapsedTimeSec / 60
                val elapsedSec = serviceState.elapsedTimeSec % 60
                val elapsedHour = elapsedMin / 60
                val formattedTime = String.format(Locale.US, "%02d:%02d:%02d", elapsedHour, elapsedMin % 60, elapsedSec)

                RowStatusItem(label = "経過時間", value = formattedTime)

                val sizeMb = serviceState.recordedBytes / (1024.0 * 1024.0)
                RowStatusItem(label = "録画サイズ", value = String.format(Locale.US, "%.2f MB", sizeMb))

                val metrics = serviceState.latestMetrics
                if (metrics != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "【リアルタイム計測メトリクス】", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    RowStatusItem(label = "バッテリー残量", value = "${metrics.batteryLevel}%")
                    RowStatusItem(label = "バッテリー温度", value = String.format(Locale.US, "%.1f ℃", metrics.batteryTempCelsius))
                    RowStatusItem(label = "Java Heap", value = "${metrics.javaHeapMb} MB")
                    RowStatusItem(label = "Native Heap", value = "${metrics.nativeHeapMb} MB")
                }

                if (serviceState.currentFile.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "保存ファイル: ${serviceState.currentFile}", fontSize = 11.sp, color = Color.Gray)
                }

                if (serviceState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "エラー: ${serviceState.errorMessage}", color = Color.Red, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons & FPS selector
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!serviceState.isRecording) {
                Text(
                    text = "【撮影FPSモード選択】",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(15, 10, 5).forEach { fps ->
                        val isSelected = selectedFps == fps
                        Button(
                            onClick = { selectedFps = fps },
                            colors = if (isSelected) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            } else {
                                ButtonDefaults.buttonColors(containerColor = Color.LightGray)
                            }
                        ) {
                            Text("${fps} fps")
                        }
                    }
                }

                Button(
                    onClick = { onStartRecording(selectedFps) },
                    enabled = hasPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("PoC-4 連続録画スタート (720p / ${selectedFps}fps)", fontSize = 16.sp)
                }
            } else {
                Button(
                    onClick = onStopRecording,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("録画ストップ & ログ出力 (${serviceState.targetFps}fps)", fontSize = 16.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "💡 テスト方法:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "1. スタートボタンを押して録画を開始します。\n" +
                            "2. 端末の電源ボタンを押し、画面ロック状態（画面OFF）にしてください。\n" +
                            "3. 2時間以上放置後、画面を復帰させストップボタンを押します。\n" +
                            "4. poc_logs ディレクトリ内に出力された CSV ログを確認します。",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun RowStatusItem(label: String, value: String, color: Color = Color.Unspecified) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text(text = value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
    }
}

private fun checkPermissions(context: Context): Boolean {
    val cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val audioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notificationPermission =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return cameraPermission && audioPermission && notificationPermission
}
