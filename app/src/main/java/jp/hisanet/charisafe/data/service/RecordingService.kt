package jp.hisanet.charisafe.data.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureRequest
import android.media.MediaScannerConnection
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
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.VideoCapture
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import jp.hisanet.charisafe.MainActivity
import jp.hisanet.charisafe.R
import jp.hisanet.charisafe.data.local.ringbuffer.Mp4Concatenator
import jp.hisanet.charisafe.data.local.ringbuffer.RingBufferManager
import jp.hisanet.charisafe.poc.PocMetricsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "charisafe_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RECORDING = "jp.hisanet.charisafe.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "jp.hisanet.charisafe.action.STOP_RECORDING"
        const val EXTRA_TARGET_FPS = "jp.hisanet.charisafe.extra.TARGET_FPS"
        const val EXTRA_RESOLUTION_WIDTH = "jp.hisanet.charisafe.extra.RESOLUTION_WIDTH"
        const val EXTRA_RESOLUTION_HEIGHT = "jp.hisanet.charisafe.extra.RESOLUTION_HEIGHT"
        const val EXTRA_AUDIO_ENABLED = "jp.hisanet.charisafe.extra.AUDIO_ENABLED"
    }

    data class ServiceState(
        val isRecording: Boolean = false,
        val targetFps: Int = 10,
        val elapsedTimeSec: Long = 0,
        val recordedBytes: Long = 0,
        val currentFile: String = "",
        val errorMessage: String? = null
    )

    private val binder = LocalBinder()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var ringBufferManager: RingBufferManager? = null

    private var targetFps: Int = 10
    private var resWidth: Int = 1280
    private var resHeight: Int = 720
    private var audioEnabled: Boolean = false

    private var metricsLogger: PocMetricsLogger? = null
    private var metricsJob: Job? = null

    private val _serviceState = MutableStateFlow(ServiceState())

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override val lifecycle: Lifecycle = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        ringBufferManager = RingBufferManager()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        targetFps = intent?.getIntExtra(EXTRA_TARGET_FPS, 10) ?: 10
        resWidth = intent?.getIntExtra(EXTRA_RESOLUTION_WIDTH, 1280) ?: 1280
        resHeight = intent?.getIntExtra(EXTRA_RESOLUTION_HEIGHT, 720) ?: 720
        audioEnabled = intent?.getBooleanExtra(EXTRA_AUDIO_ENABLED, false) ?: false

        Log.i(TAG, "onStartCommand action: $action, targetFps: $targetFps, res: ${resWidth}x${resHeight}")

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
            .setContentTitle("ChariSafe 録画稼働中")
            .setContentText("${resHeight}p / ${targetFps}fps 安全録画中")
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
                "ChariSafe:RecordingWakeLock"
            ).apply {
                acquire(12 * 60 * 60 * 1000L)
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

        metricsLogger = PocMetricsLogger(this, "ChariSafe-${targetFps}fps")
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

        val quality = when (resHeight) {
            480 -> Quality.SD
            1080 -> Quality.FHD
            else -> Quality.HD
        }

        val recorderBuilder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))

        val recorder = recorderBuilder.build()

        val videoCaptureBuilder = VideoCapture.Builder(recorder)

        val camera2Interop = Camera2Interop.Extender(videoCaptureBuilder)
        camera2Interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(targetFps, targetFps)
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
            Log.i(TAG, "Bound CameraX VideoCapture with ${resHeight}p / ${targetFps}fps Target Range")

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

        val segmentFile = ringBufferManager?.createNextSegmentFile() ?: return

        val outputOptions = FileOutputOptions.Builder(segmentFile).build()

        _serviceState.value = _serviceState.value.copy(
            isRecording = true,
            targetFps = targetFps,
            currentFile = segmentFile.absolutePath,
            errorMessage = null
        )

        val pendingRecording = capture.output.prepareRecording(this, outputOptions)
        if (audioEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            @SuppressLint("MissingPermission")
            pendingRecording.withAudioEnabled()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            handleRecordEvent(event, segmentFile.absolutePath)
        }

        startMetricsLoggingJob()
    }

    /**
     * 【重要】ユーザー様ご指摘の正確なシーケンス:
     * イベント保存のタイミングでリングバッファ撮影を完全に停止し、
     * ファイルクローズ完了を待ってから動画結合を行い、その後リングバッファ撮影を自動再開する。
     */
    fun exportEventAndResume(beforeMinutes: Int, onComplete: (File?) -> Unit) {
        serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "=== Safe Event Export & Resume Workflow Started ===")

            val currentFilePath = _serviceState.value.currentFile
            val currentFile = File(currentFilePath)

            // 1. リングバッファ撮影（現在書き込み中の録画）を確実に完全停止する
            withContext(Dispatchers.Main) {
                activeRecording?.stop()
                activeRecording = null
            }
            delay(timeMillis = 1000L) // ディスクへのファイルフラッシュとクローズ完了を確実に待機

            // 2. 閉じたばかりの現在ファイルを含む、確定済みセグメントを取得
            val segments = ringBufferManager?.getRecentSegmentsForExport(beforeMinutes) ?: emptyList()
            val validSegments = if (currentFile.exists() && currentFile.length() > 0 && !segments.contains(currentFile)) {
                segments + currentFile
            } else {
                segments
            }

            val recordingsDir = jp.hisanet.charisafe.util.FileUtils.getRecordingsDir()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val eventFile = File(recordingsDir, "event_${timestamp}.mp4")

            // 3. 動画結合・保存 (100%再生保証)
            val success = Mp4Concatenator.concatenate(validSegments, eventFile)
            if (success) {
                MediaScannerConnection.scanFile(this@RecordingService, arrayOf(eventFile.absolutePath), null, null)
                Log.i(TAG, "Successfully exported event video and scanned: ${eventFile.absolutePath}")
            } else {
                Log.e(TAG, "Failed to export event video")
            }

            // 4. リングバッファ録画を自動再開
            withContext(Dispatchers.Main) {
                Log.i(TAG, "=== Resuming ring buffer recording ===")
                setupVideoCaptureAndStart()
            }

            onComplete(if (success) eventFile else null)
        }
    }

    private fun handleRecordEvent(event: VideoRecordEvent, filePath: String) {
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.i(TAG, "Started recording segment: $filePath")
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
                    Log.e(TAG, "Segment recording finalized with error: ${event.error}")
                    _serviceState.value = _serviceState.value.copy(
                        isRecording = false,
                        errorMessage = "録画エラー発生: code=${event.error}"
                    )
                } else {
                    Log.i(TAG, "Segment recording finalized successfully: $filePath")
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
                metricsLogger?.captureMetrics(details)
                delay(timeMillis = 15_000L)
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
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
