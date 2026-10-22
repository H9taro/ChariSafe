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
import androidx.camera.core.ImageAnalysis
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
import jp.hisanet.charisafe.data.camera.RearApproachDetector
import jp.hisanet.charisafe.data.local.ringbuffer.Mp4Concatenator
import jp.hisanet.charisafe.data.local.ringbuffer.RingBufferManager
import jp.hisanet.charisafe.domain.model.ActiveCamera
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
import java.util.concurrent.Executors

class RecordingService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "charisafe_recording_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RECORDING = "jp.hisanet.charisafe.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "jp.hisanet.charisafe.action.STOP_RECORDING"
        const val EXTRA_ACTIVE_CAMERA = "jp.hisanet.charisafe.extra.ACTIVE_CAMERA"
        const val EXTRA_RING_BUFFER_MINUTES = "jp.hisanet.charisafe.extra.RING_BUFFER_MINUTES"
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
    private var imageAnalysis: ImageAnalysis? = null
    private var activeRecording: Recording? = null
    private var ringBufferManager: RingBufferManager? = null

    private var activeCamera: ActiveCamera = ActiveCamera.REAR
    private var ringBufferMinutes: Int = 5
    private var targetFps: Int = 10
    private var resWidth: Int = 1280
    private var resHeight: Int = 720
    private var audioEnabled: Boolean = false

    private var metricsLogger: PocMetricsLogger? = null
    private var metricsJob: Job? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

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

        ringBufferManager = RingBufferManager(ringBufferMinutes)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val camName = intent?.getStringExtra(EXTRA_ACTIVE_CAMERA) ?: ActiveCamera.REAR.name
        activeCamera = try { ActiveCamera.valueOf(camName) } catch (e: Exception) { ActiveCamera.REAR }
        ringBufferMinutes = intent?.getIntExtra(EXTRA_RING_BUFFER_MINUTES, 5) ?: 5
        targetFps = intent?.getIntExtra(EXTRA_TARGET_FPS, 10) ?: 10
        resWidth = intent?.getIntExtra(EXTRA_RESOLUTION_WIDTH, 1280) ?: 1280
        resHeight = intent?.getIntExtra(EXTRA_RESOLUTION_HEIGHT, 720) ?: 720
        audioEnabled = intent?.getBooleanExtra(EXTRA_AUDIO_ENABLED, false) ?: false

        ringBufferManager = RingBufferManager(ringBufferMinutes)

        Log.i(TAG, "onStartCommand action: $action, camera: $activeCamera, ringBufferMinutes: $ringBufferMinutes")

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

        val camLabel = if (activeCamera == ActiveCamera.REAR) "後方(リア)" else "前方(フロント)"
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChariSafe 録画稼働中 ($camLabel)")
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

        ringBufferManager?.clearAll()
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
        val cameraSelector = if (activeCamera == ActiveCamera.REAR) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        try {
            provider.unbindAll()

            // カメラに依存せず、常に ImageAnalysis (接近・急変動検知) を VideoCapture と同時にバインド
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(analysisExecutor, RearApproachDetector {
                        Log.w(TAG, "🚨 [Priority2] 接近検知発火 -> 自動イベント保存を実行します")
                        exportEventAsync(ringBufferMinutes) { file ->
                            Log.i(TAG, "Auto-saved event video on approach: ${file?.absolutePath}")
                        }
                    })
                }

            provider.bindToLifecycle(
                this,
                cameraSelector,
                videoCapture,
                imageAnalysis
            )
            Log.i(TAG, "Bound CameraX VideoCapture + ApproachAnalyzer ($activeCamera)")

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

    fun exportEventAsync(beforeMinutes: Int, onComplete: (File?) -> Unit) {
        serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "=== Async Event Export Workflow Started ===")

            val currentFilePath = _serviceState.value.currentFile
            val currentFile = File(currentFilePath)

            withContext(Dispatchers.Main) {
                activeRecording?.stop()
                activeRecording = null
            }
            delay(500L)

            val segments = ringBufferManager?.getRecentSegmentsForExport(beforeMinutes) ?: emptyList()
            val validSegments = if (currentFile.exists() && currentFile.length() > 0 && !segments.contains(currentFile)) {
                segments + currentFile
            } else {
                segments
            }

            val workingDir = File(cacheDir, "working_segments").apply { if (!exists()) mkdirs() }
            val workingFiles = mutableListOf<File>()

            for (seg in validSegments) {
                if (seg.exists() && seg.length() > 0) {
                    val destFile = File(workingDir, seg.name)
                    seg.copyTo(destFile, overwrite = true)
                    workingFiles.add(destFile)
                }
            }

            withContext(Dispatchers.Main) {
                Log.i(TAG, "Resuming ring buffer recording immediately...")
                setupVideoCaptureAndStart()
            }

            val recordingsDir = jp.hisanet.charisafe.util.FileUtils.getRecordingsDir()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val eventFile = File(recordingsDir, "event_${timestamp}.mp4")

            val success = Mp4Concatenator.concatenate(workingFiles, eventFile)

            try {
                workingDir.deleteRecursively()
            } catch (ignored: Exception) {}

            if (success) {
                MediaScannerConnection.scanFile(this@RecordingService, arrayOf(eventFile.absolutePath), null, null)
                Log.i(TAG, "Successfully concatenated and saved event video: ${eventFile.absolutePath}")
            } else {
                Log.e(TAG, "Failed to concatenate event video")
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
        analysisExecutor.shutdown()
        stopRecordingPipeline()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
