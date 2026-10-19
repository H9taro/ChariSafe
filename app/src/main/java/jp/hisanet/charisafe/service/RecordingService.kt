package jp.hisanet.charisafe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureRequest
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import jp.hisanet.charisafe.MainActivity
import jp.hisanet.charisafe.R
import jp.hisanet.charisafe.poc.PocMetrics
import jp.hisanet.charisafe.poc.PocMetricsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class RecordingService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "charisafe_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RECORDING = "jp.hisanet.charisafe.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "jp.hisanet.charisafe.action.STOP_RECORDING"
    }

    data class ServiceState(
        val isRecording: Boolean = false,
        val elapsedTimeSec: Long = 0,
        val recordedBytes: Long = 0,
        val currentFile: String = "",
        val latestMetrics: PocMetrics? = null,
        val errorMessage: String? = null
    )

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var metricsLogger: PocMetricsLogger? = null
    private var metricsJob: Job? = null

    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override val lifecycle: Lifecycle
        field = LifecycleRegistry(this)

    override fun onCreate() {
        super.onCreate()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        metricsLogger = PocMetricsLogger(this, "PoC-1")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START_RECORDING -> {
                startForegroundWithNotification()
                acquireWakeLock()
                startRecordingPipeline()
            }
            ACTION_STOP_RECORDING -> {
                stopRecordingPipeline()
                stopForegroundAndSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ChariSafe Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ChariSafe Background Recording Status"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChariSafe PoC-1 録画中")
            .setContentText("バックグラウンドで720p/15fpsで連続録画中...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ChariSafe:PoC1RecordingWakeLock"
            ).apply {
                acquire(12 * 60 * 60 * 1000L) // 最大12時間
            }
            Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Released PARTIAL_WAKE_LOCK")
            }
        }
        wakeLock = null
    }

    private fun startRecordingPipeline() {
        if (_serviceState.value.isRecording) {
            Log.w(TAG, "Recording is already in progress")
            return
        }

        metricsLogger?.startSession()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupVideoCaptureAndStart()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize CameraProvider", e)
                _serviceState.value = _serviceState.value.copy(
                    errorMessage = "CameraProvider初期化失敗: ${e.message}"
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setupVideoCaptureAndStart() {
        val provider = cameraProvider ?: return

        // 720p (HD) Quality Selector
        val recorderBuilder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))

        val recorder = recorderBuilder.build()

        // VideoCapture Builder with Camera2Interop for 15 FPS Target
        val videoCaptureBuilder = VideoCapture.Builder(recorder)

        // Camera2Interop によりフレームレートを15fpsに制限 (Target FPS Range: 15..15)
        val camera2Interop = Camera2Interop.Extender(videoCaptureBuilder)
        camera2Interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(15, 15)
        )

        videoCapture = videoCaptureBuilder.build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                cameraSelector,
                videoCapture
            )
            Log.i(TAG, "Bound CameraX VideoCapture with 15fps Target Range to Lifecycle")

            startVideoFileOutput()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind VideoCapture use case", e)
            _serviceState.value = _serviceState.value.copy(
                errorMessage = "カメラバインド失敗: ${e.message}"
            )
        }
    }

    private fun startVideoFileOutput() {
        val capture = videoCapture ?: return

        val dir = File(getExternalFilesDir(null), "poc_recordings")
        if (!dir.exists()) dir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val videoFile = File(dir, "PoC1_${timestamp}.mp4")

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        _serviceState.value = _serviceState.value.copy(
            isRecording = true,
            currentFile = videoFile.absolutePath,
            errorMessage = null
        )

        activeRecording = capture.output
            .prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                handleRecordEvent(event, videoFile.absolutePath)
            }

        startMetricsLoggingJob()
    }

    private fun handleRecordEvent(event: VideoRecordEvent, filePath: String) {
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.i(TAG, "Started recording to file: $filePath")
            }
            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                _serviceState.value = _serviceState.value.copy(
                    recordedBytes = stats.numBytesRecorded,
                    elapsedTimeSec = stats.recordedDurationNanos / 1_000_000_000L
                )
            }
            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    Log.e(TAG, "Recording finalized with error: ${event.error}")
                    _serviceState.value = _serviceState.value.copy(
                        isRecording = false,
                        errorMessage = "録画エラー発生: code=${event.error}"
                    )
                } else {
                    Log.i(TAG, "Recording finalized successfully: $filePath")
                    _serviceState.value = _serviceState.value.copy(
                        isRecording = false
                    )
                }
            }
        }
    }

    private fun startMetricsLoggingJob() {
        metricsJob?.cancel()
        metricsJob = serviceScope.launch {
            while (_serviceState.value.isRecording) {
                val currentState = _serviceState.value
                val details = "Bytes:${currentState.recordedBytes}, File:${File(currentState.currentFile).name}"
                val metrics = metricsLogger?.captureMetrics(details)

                _serviceState.value = _serviceState.value.copy(
                    latestMetrics = metrics
                )
                delay(15_000L.milliseconds) // 15秒ごとに計測
            }
        }
    }

    private fun stopRecordingPipeline() {
        metricsJob?.cancel()
        metricsJob = null

        activeRecording?.stop()
        activeRecording = null

        cameraProvider?.unbindAll()
        releaseWakeLock()

        _serviceState.value = _serviceState.value.copy(
            isRecording = false
        )
        Log.i(TAG, "Stopped recording pipeline")
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRecordingPipeline()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
