package jp.hisanet.charisafe.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import jp.hisanet.charisafe.data.local.ringbuffer.RingBufferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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

    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var ringBufferManager: RingBufferManager? = null

    private var targetFps: Int = 10
    private var resWidth: Int = 1280
    private var resHeight: Int = 720

    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        ringBufferManager = RingBufferManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        targetFps = intent?.getIntExtra(EXTRA_TARGET_FPS, 10) ?: 10
        resWidth = intent?.getIntExtra(EXTRA_RESOLUTION_WIDTH, 1280) ?: 1280
        resHeight = intent?.getIntExtra(EXTRA_RESOLUTION_HEIGHT, 720) ?: 720

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    private fun startForegroundWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChariSafe 録画稼働中")
            .setContentText("${resHeight}p / ${targetFps}fps バックグラウンド安全録画中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(resWidth, resHeight),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )

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
            Log.i(TAG, "Bound CameraX VideoCapture with ${resHeight}p / ${targetFps}fps Target Range to Lifecycle")

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

        activeRecording = capture.output
            .prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                handleRecordEvent(event, segmentFile.absolutePath)
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

    private fun stopRecordingPipeline() {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
