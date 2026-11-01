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
import androidx.camera.video.FallbackStrategy
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
import jp.hisanet.charisafe.data.camera.RearApproachDetector
import jp.hisanet.charisafe.data.config.ConfigRepositoryImpl
import jp.hisanet.charisafe.data.debug.ApproachDebugLogger
import jp.hisanet.charisafe.data.debug.CollisionDebugLogger
import jp.hisanet.charisafe.data.debug.DebugLogCleanupManager
import jp.hisanet.charisafe.data.debug.TraceLayer
import jp.hisanet.charisafe.data.debug.TraceLogger
import jp.hisanet.charisafe.data.debug.TraceMetric
import jp.hisanet.charisafe.data.local.EventDetectionLogStore
import jp.hisanet.charisafe.data.local.IncidentLogStore
import jp.hisanet.charisafe.data.local.ringbuffer.RingBufferManager
import jp.hisanet.charisafe.data.location.LocationTracker
import jp.hisanet.charisafe.data.sensor.CollisionDropDetector
import jp.hisanet.charisafe.domain.model.ActiveCamera
import jp.hisanet.charisafe.domain.model.EventDetectionLog
import jp.hisanet.charisafe.domain.model.EventSensitivity
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.IncidentLog
import jp.hisanet.charisafe.domain.model.IncidentStatus
import jp.hisanet.charisafe.domain.model.ProtectionFailureReason
import jp.hisanet.charisafe.domain.model.ProtectionReason
import jp.hisanet.charisafe.domain.model.RecordingSegment
import jp.hisanet.charisafe.domain.model.SegmentProtectionTraceEntry
import jp.hisanet.charisafe.domain.repository.ConfigRepository
import jp.hisanet.charisafe.util.FileUtils
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val RING_BUFFER_INTERVAL_MS = 60_000L

class RecordingService : Service(), LifecycleOwner {
    private lateinit var traceLogger: TraceLogger

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
    private var rearApproachDetector: RearApproachDetector? = null
    private var collisionDropDetector: CollisionDropDetector? = null

    private val _soundAmplitudeState = MutableStateFlow(0f)
    private val _approachScoreState = MutableStateFlow(0f)
    private var approachCollectJob: Job? = null
    var isAudioMuted = false

    val approachScoreState: StateFlow<Float>
        get() = _approachScoreState.asStateFlow()
    val gForceState: StateFlow<Float>
        get() = collisionDropDetector?.gForceState ?: MutableStateFlow(0f)
    val soundAmplitudeState: StateFlow<Float>
        get() = _soundAmplitudeState.asStateFlow()
    private var approachDebugLogger: ApproachDebugLogger? = null
    private var collisionDebugLogger: CollisionDebugLogger? = null
    private var recordingStartTimeMs: Long = 0L
    private var recordingStopContinuation: CancellableContinuation<Unit>? = null
    private var statusEventCount = 0

    private val activeSegments =
        java.util.Collections.synchronizedMap(mutableMapOf<String, RecordingSegment>())

    private val configRepository: ConfigRepository by lazy { ConfigRepositoryImpl(this) }

    private var activeCamera: ActiveCamera = ActiveCamera.REAR
    private var ringBufferMinutes: Int = 5
    private var targetFps: Int = 10
    private var userConfigFps: Int = 10
    private var resWidth: Int = 1280
    private var resHeight: Int = 720
    private var audioEnabled: Boolean = true

    private var rotationJob: Job? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val _serviceState = MutableStateFlow(ServiceState())

    private data class IncidentWindow(
        val incidentId: String,
        val targetSegmentPaths: MutableSet<String>,
        val relatedDetectionIds: MutableList<String>,
        var highestSeverityType: EventType
    )

    private val activeIncidentWindows =
        java.util.Collections.synchronizedMap(mutableMapOf<String, IncidentWindow>())

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override val lifecycle: Lifecycle = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        traceLogger = TraceLogger.getInstance(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        ringBufferManager = RingBufferManager(this, ringBufferMinutes)
        createNotificationChannel()
        DebugLogCleanupManager.cleanOldLogs(this)
        approachDebugLogger = ApproachDebugLogger(this)
        collisionDebugLogger = CollisionDebugLogger(this)

        collisionDropDetector = CollisionDropDetector(this, collisionDebugLogger) {
            val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
            val sens = prefs.getFloat("key_collision_sensitivity", 3.0f)
            handleDetectedEvent(
                EventType.COLLISION,
                sens,
                "${String.format(Locale.US, "%.1f", sens)}${EventType.COLLISION.unitName}"
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            Log.w(TAG, "onStartCommand received null action, ignoring to prevent redelivery bugs")
            return START_NOT_STICKY
        }

        // インテントまたは設定リポジトリからカメラ選択を確実に同期
        val camName = intent.getStringExtra(EXTRA_ACTIVE_CAMERA)
        activeCamera = if (camName != null) {
            try {
                ActiveCamera.valueOf(camName)
            } catch (e: Exception) {
                configRepository.getConfig().activeCamera
            }
        } else {
            configRepository.getConfig().activeCamera
        }
        ringBufferMinutes = intent.getIntExtra(EXTRA_RING_BUFFER_MINUTES, 5)
        targetFps = intent.getIntExtra(EXTRA_TARGET_FPS, 10)
        userConfigFps = targetFps
        resWidth = intent.getIntExtra(EXTRA_RESOLUTION_WIDTH, 1280)
        resHeight = intent.getIntExtra(EXTRA_RESOLUTION_HEIGHT, 720)
        audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, true)

        ringBufferManager = RingBufferManager(this, ringBufferMinutes)

        Log.i(
            TAG,
            "onStartCommand action: $action, camera: $activeCamera, ringBufferMinutes: $ringBufferMinutes"
        )

        when (action) {
            ACTION_START_RECORDING -> {
                recordingStartTimeMs = System.currentTimeMillis()
                startForegroundWithNotification()
                acquireWakeLock()
                collisionDropDetector?.startListening()
                startRecordingPipeline()
                startSegmentRotationTimer()
            }

            ACTION_STOP_RECORDING -> {
                collisionDropDetector?.stopListening()
                serviceScope.launch {
                    stopRecordingPipeline()
                    stopForegroundAndSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun startSegmentRotationTimer() {
        rotationJob?.cancel()
        rotationJob = serviceScope.launch(Dispatchers.IO) {
            while (true) {
                delay(RING_BUFFER_INTERVAL_MS) // セグメント切替間隔
                if (_serviceState.value.isRecording) {
                    Log.i(
                        TAG,
                        "⏱️ ${RING_BUFFER_INTERVAL_MS}ミリ秒経過したため、リングバッファセグメントを自動ローテーション（切替）します"
                    )
                    withContext(Dispatchers.Main) {
                        activeRecording?.stop()
                        activeRecording = null
                    }
                }
            }
        }
    }

    private fun stopSegmentRotationTimer() {
        rotationJob?.cancel()
        rotationJob = null
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
        val silentChannelId = "charisafe_silent_channel"
        val channel = NotificationChannel(
            silentChannelId,
            "Background Service",
            NotificationManager.IMPORTANCE_NONE
        ).apply {
            description = "Background Service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, silentChannelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.color.transparent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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

        val sessionId = "REC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        traceLogger.startSession(sessionId)

        ringBufferManager?.clearAll()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupVideoCaptureAndStart()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera provider", e)
                _serviceState.value = _serviceState.value.copy(
                    errorMessage = "カメラ初期化失敗: ${e.message}"
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setupVideoCaptureAndStart() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val currentRes = configRepository.getConfig().resolution
        resWidth = currentRes.width
        resHeight = currentRes.height

        val targetQuality = when {
            resWidth >= 1920 || resHeight >= 1080 -> Quality.FHD
            resWidth >= 1280 || resHeight >= 720 -> Quality.HD
            else -> Quality.SD
        }

        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(targetQuality, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )

        val recorderBuilder = Recorder.Builder()
        recorderBuilder.setQualitySelector(qualitySelector)
        val recorder = recorderBuilder.build()

        Log.i(TAG, "📹 CameraX 動画解像度設定: targetQuality=$targetQuality ($resWidth x $resHeight)")

        val videoCaptureBuilder = VideoCapture.Builder(recorder)
        val camera2Interop = Camera2Interop.Extender(videoCaptureBuilder)
        camera2Interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(targetFps, targetFps)
        )

        videoCapture = videoCaptureBuilder.build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        val detector = RearApproachDetector(approachDebugLogger, {
            val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
            prefs.getFloat("key_approach_sensitivity", 100f)
        }) {
            val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
            val sens = prefs.getFloat("key_approach_sensitivity", 100f)
            val sensString = String.format(Locale.US, "%.1f", sens)
            handleDetectedEvent(EventType.APPROACH, sens, "${sensString}${EventType.APPROACH.unitName}")
        }
        rearApproachDetector = detector
        approachCollectJob?.cancel()
        approachCollectJob = serviceScope.launch {
            detector.approachScoreState.collect { score ->
                _approachScoreState.value = score.toFloat()
                traceLogger.log(TraceMetric.APPROACH, TraceLayer.SERVICE, score.toFloat())
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                Log.i("ApproachTrace", "$timeStr\nService=$score")
            }
        }

        analysis.setAnalyzer(analysisExecutor, detector)

        imageAnalysis = analysis

        val cameraSelector = if (activeCamera == ActiveCamera.REAR) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        try {
            provider.bindToLifecycle(
                this,
                cameraSelector,
                videoCapture,
                imageAnalysis
            )
            Log.i(
                TAG,
                "Bound CameraX VideoCapture + Analyzer ($activeCamera) at targetFps=$targetFps"
            )
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
        if (audioEnabled && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            @SuppressLint("MissingPermission")
            pendingRecording.withAudioEnabled()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            handleRecordEvent(event, segmentFile.absolutePath)
        }
    }

    fun triggerAutoEventWithAlert(reason: String) {
        val eventType = when (reason) {
            "後方接近検知", "接近" -> EventType.APPROACH
            "急減速検知", "減速" -> EventType.DECELERATION
            "衝突・落下検知", "衝撃" -> EventType.COLLISION
            "大きな音(衝突音)検知", "音量" -> EventType.SOUND
            else -> EventType.APPROACH
        }
        val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
        val sens = when (eventType) {
            EventType.APPROACH -> prefs.getFloat("key_approach_sensitivity", 0.7f)
            EventType.DECELERATION -> prefs.getFloat("key_decel_sensitivity", 1.5f)
            EventType.COLLISION -> prefs.getFloat("key_collision_sensitivity", 3.0f)
            EventType.SOUND -> (prefs.getFloat("key_sound_sensitivity", 5000.0f)) / 50f
        }
        val label = "${sens}${eventType.unitName}"
        handleDetectedEvent(eventType, sens, label)
    }

    fun handleDetectedEvent(
        eventType: EventType,
        sensitivityValue: Float,
        sensitivityLabel: String
    ) {
        val now = System.currentTimeMillis()
        if (now - recordingStartTimeMs < 3000L) return

        val lat = LocationTracker.lastValidLatitude
        val lon = LocationTracker.lastValidLongitude
        val speedKmh = LocationTracker.lastValidSpeedKmh
        val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
        val snapshot = EventSensitivity(
            approachSensitivity = prefs.getFloat("key_approach_sensitivity", 0.7f),
            decelerationSensitivity = prefs.getFloat("key_decel_sensitivity", 1.5f),
            collisionSensitivity = prefs.getFloat("key_collision_sensitivity", 3.0f),
            soundSensitivity = prefs.getFloat("key_sound_sensitivity", 0.7f)
        )

        val detectionLog = EventDetectionLog(
            timestampMs = now,
            eventType = eventType,
            speedKmh = speedKmh,
            latitude = lat,
            longitude = lon,
            sensitivityValue = sensitivityValue,
            sensitivityLabel = sensitivityLabel
        )

        val existingDetections = EventDetectionLogStore.loadLogs(this).toMutableList()
        existingDetections.add(0, detectionLog)
        EventDetectionLogStore.saveLogs(this, existingDetections)

        serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "ℹ️ ${eventType.displayName}検知: セグメント保護を実行します")

            val currentFilePath = _serviceState.value.currentFile
            val currentFileObj = File(currentFilePath)
            val currentSegmentName = if (currentFileObj.exists()) currentFileObj.name else ""
            detectionLog.segmentName = currentSegmentName

            // 1. 一世代前のセグメント（完了済み）を取得して即時保護を試みる
            val previousCompletedFile =
                ringBufferManager?.getPreviousCompletedSegmentFile(currentFilePath)
            val coveredFiles = mutableListOf<String>()

            if (previousCompletedFile != null) {
                var prevSeg = activeSegments[previousCompletedFile.name]
                if (prevSeg == null) {
                    prevSeg = RecordingSegment(
                        segmentName = previousCompletedFile.name,
                        filePath = previousCompletedFile.absolutePath,
                        startTimeMs = previousCompletedFile.lastModified() - RING_BUFFER_INTERVAL_MS,
                        endTimeMs = previousCompletedFile.lastModified(),
                        isProtected = false
                    )
                    activeSegments[previousCompletedFile.name] = prevSeg
                }

                // ケース1: 一世代前セグメントが未保護 ➔ 即時保護 ＆ 即時通知
                if (!prevSeg.isProtected) {
                    try {
                        val recordingsDir = FileUtils.getRecordingsDir(this@RecordingService)
                        val destFile = File(recordingsDir, previousCompletedFile.name)
                        previousCompletedFile.copyTo(destFile, overwrite = true)
                        prevSeg.isProtected = true
                        MediaScannerConnection.scanFile(
                            this@RecordingService,
                            arrayOf(destFile.absolutePath),
                            null,
                            null
                        )
                        coveredFiles.add(destFile.absolutePath)

                        traceLogger.logProtectionTrace(
                            SegmentProtectionTraceEntry(
                                segmentName = prevSeg.segmentName,
                                isProtected = true,
                                pendingProtection = false,
                                triggerEventType = eventType.name,
                                protectionReason = ProtectionReason.PREVIOUS_SEGMENT_AUTO_PROTECTION,
                                notificationSent = true,
                                operationSucceeded = true,
                                associatedEventId = detectionLog.id
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy previous segment", e)
                        traceLogger.logProtectionTrace(
                            SegmentProtectionTraceEntry(
                                segmentName = prevSeg.segmentName,
                                isProtected = false,
                                pendingProtection = false,
                                triggerEventType = eventType.name,
                                protectionReason = ProtectionReason.PREVIOUS_SEGMENT_AUTO_PROTECTION,
                                notificationSent = false,
                                operationSucceeded = false,
                                failureReason = ProtectionFailureReason.FILE_COPY_FAILED,
                                associatedEventId = detectionLog.id
                            )
                        )
                    }
                } else {
                    // ケース2: 一世代前セグメントがすでに保護済み ➔ 追加通知・保護はスキップ (通知スパム抑制)
                    coveredFiles.add(
                        File(
                            FileUtils.getRecordingsDir(this@RecordingService),
                            previousCompletedFile.name
                        ).absolutePath
                    )
                    traceLogger.logProtectionTrace(
                        SegmentProtectionTraceEntry(
                            segmentName = prevSeg.segmentName,
                            isProtected = true,
                            pendingProtection = false,
                            triggerEventType = eventType.name,
                            protectionReason = ProtectionReason.ALREADY_PROTECTED_SKIP,
                            notificationSent = false,
                            operationSucceeded = true,
                            associatedEventId = detectionLog.id
                        )
                    )
                }
            }

            // 2. 現行セグメント（書き込み中）に保護予約フラグを立てる
            if (currentSegmentName.isNotEmpty()) {
                val curSeg = activeSegments.getOrPut(currentSegmentName) {
                    RecordingSegment(
                        segmentName = currentSegmentName,
                        filePath = currentFilePath,
                        startTimeMs = now
                    )
                }
                curSeg.pendingProtection = true
                coveredFiles.add(currentFilePath)

                traceLogger.logProtectionTrace(
                    SegmentProtectionTraceEntry(
                        segmentName = currentSegmentName,
                        isProtected = curSeg.isProtected,
                        pendingProtection = true,
                        triggerEventType = eventType.name,
                        protectionReason = ProtectionReason.PENDING_PROTECTION_SET,
                        notificationSent = false,
                        operationSucceeded = true,
                        associatedEventId = detectionLog.id
                    )
                )
            }

            val candidateSet = coveredFiles.toSet()
            val existingIncidents =
                IncidentLogStore.loadIncidents(this@RecordingService).toMutableList()

            // インシデント統合判定
            var targetWindow: IncidentWindow? = null
            var targetIncident: IncidentLog? = null

            for ((_, window) in activeIncidentWindows) {
                if (window.targetSegmentPaths.any { candidateSet.contains(it) } || candidateSet.any {
                        window.targetSegmentPaths.contains(
                            it
                        )
                    }) {
                    targetWindow = window
                    targetIncident =
                        existingIncidents.find { it.id == window.incidentId && it.status == IncidentStatus.PENDING }
                    break
                }
            }

            if (targetWindow != null && targetIncident != null) {
                targetWindow.relatedDetectionIds.add(detectionLog.id)
                targetWindow.highestSeverityType =
                    getHigherSeverity(targetWindow.highestSeverityType, eventType)
                targetWindow.targetSegmentPaths.addAll(candidateSet)

                targetIncident.relatedDetectionIds = targetWindow.relatedDetectionIds.toList()
                targetIncident.highestSeverityType = targetWindow.highestSeverityType
                targetIncident.coveredSegmentFiles = targetWindow.targetSegmentPaths.toList()

                detectionLog.incidentId = targetIncident.id
                val updatedDetections =
                    EventDetectionLogStore.loadLogs(this@RecordingService).toMutableList()
                updatedDetections.forEach { det ->
                    if (targetIncident.relatedDetectionIds.contains(det.id)) {
                        det.incidentId = targetIncident.id
                    }
                }
                EventDetectionLogStore.saveLogs(this@RecordingService, updatedDetections)

                val idx = existingIncidents.indexOfFirst { it.id == targetIncident.id }
                if (idx >= 0) existingIncidents[idx] = targetIncident
                IncidentLogStore.saveIncidents(this@RecordingService, existingIncidents)
                Log.i(TAG, "🔄 既存PENDINGインシデントへ統合: ${targetIncident.id}")
            } else {
                val newIncident = IncidentLog(
                    timestampMs = now,
                    triggerEventType = eventType,
                    triggerDetectionLogId = detectionLog.id,
                    relatedDetectionIds = mutableListOf(detectionLog.id),
                    highestSeverityType = eventType,
                    latitude = lat,
                    longitude = lon,
                    videoFilePath = "",
                    coveredSegmentFiles = emptyList(),
                    status = IncidentStatus.PENDING
                )

                detectionLog.incidentId = newIncident.id
                val updatedDetections =
                    EventDetectionLogStore.loadLogs(this@RecordingService).toMutableList()
                updatedDetections.forEach { det ->
                    if (newIncident.relatedDetectionIds.contains(det.id)) {
                        det.incidentId = newIncident.id
                    }
                }
                EventDetectionLogStore.saveLogs(this@RecordingService, updatedDetections)

                existingIncidents.add(0, newIncident)
                IncidentLogStore.saveIncidents(this@RecordingService, existingIncidents)

                val newWindow = IncidentWindow(
                    incidentId = newIncident.id,
                    targetSegmentPaths = candidateSet.toMutableSet(),
                    relatedDetectionIds = mutableListOf(detectionLog.id),
                    highestSeverityType = eventType
                )
                activeIncidentWindows[newIncident.id] = newWindow

                Log.i(TAG, "✨ 新規PENDINGインシデントウィンドウ作成: ${newIncident.id}")
            }

            // 一世代前セグメントが保護された場合のみ即時通知を発報
            if (previousCompletedFile != null && activeSegments[previousCompletedFile.name]?.isProtected == true) {
                val incToNotify = existingIncidents.find {
                    it.id == targetIncident?.id ?: activeIncidentWindows.values.firstOrNull { w ->
                        w.targetSegmentPaths.contains(previousCompletedFile.absolutePath)
                    }?.incidentId
                }
                if (incToNotify != null) {
                    showRecordingCompletionNotification(incToNotify)
                    Log.i(TAG, "🔔 一世代前セグメント即時保護通知を発報しました: ${incToNotify.id}")
                }
            }
        }
    }

    private fun getHigherSeverity(a: EventType, b: EventType): EventType {
        val rank = mapOf(
            EventType.COLLISION to 4,
            EventType.DECELERATION to 3,
            EventType.SOUND to 2,
            EventType.APPROACH to 1
        )
        val rankA = rank[a] ?: 0
        val rankB = rank[b] ?: 0
        return if (rankB > rankA) b else a
    }

    private fun handleRecordEvent(event: VideoRecordEvent, filePath: String) {
        Log.i(
            "RecordEventTrace",
            "event=${event.javaClass.simpleName}"
        )
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.i(TAG, "Started recording segment: $filePath")
            }

            is VideoRecordEvent.Status -> {
                statusEventCount++
                val stats = event.recordingStats
                val audioStats = stats.audioStats
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

                Log.i("AudioStatTrace", "$timeStr\nStatus entered")
                Log.i("AudioStatTrace", "$timeStr\nstatsClass=${stats.javaClass.name}")
                Log.i("AudioStatTrace", "$timeStr\naudioStats=$audioStats")

                val audioAmplitude = audioStats.audioAmplitude
                val audioAmp = audioAmplitude.toFloat().coerceIn(0f, 32767f)
                Log.i("AudioStatTrace", "$timeStr\naudioAmplitude=$audioAmplitude")

                _soundAmplitudeState.value = audioAmp
                traceLogger.log(TraceMetric.SOUND, TraceLayer.SERVICE, audioAmp)
                Log.i("AudioCompare", "cameraXAmplitude=$audioAmp")

                if (!isAudioMuted) {
                    val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
                    val soundSensitivity = prefs.getFloat("key_sound_sensitivity", 0.6f)
                    if (audioAmp >= soundSensitivity) {
                        handleDetectedEvent(
                            EventType.SOUND,
                            audioAmp,
                            "${((audioAmp / 1.5f) * 100).toInt()}${EventType.SOUND.unitName}"
                        )
                    }
                }

                _serviceState.value = _serviceState.value.copy(
                    recordedBytes = stats.numBytesRecorded,
                    elapsedTimeSec = stats.recordedDurationNanos / 1_000_000_000L
                )
            }

            is VideoRecordEvent.Finalize -> {
                recordingStopContinuation?.resume(Unit)
                recordingStopContinuation = null

                if (event.hasError()) {
                    Log.i(TAG, "Segment recording finalized with error: ${event.error}")
                    _serviceState.value = _serviceState.value.copy(
                        isRecording = false,
                        errorMessage = "録画エラー発生: code=${event.error}"
                    )
                } else {
                    Log.i(TAG, "Segment recording finalized successfully: $filePath")

                    serviceScope.launch(Dispatchers.IO) {
                        val finalizedFile = File(filePath)
                        val seg = activeSegments[finalizedFile.name]

                        if (seg != null && seg.pendingProtection) {
                            try {
                                val recordingsDir =
                                    FileUtils.getRecordingsDir(this@RecordingService)
                                val destFile = File(recordingsDir, finalizedFile.name)
                                finalizedFile.copyTo(destFile, overwrite = true)
                                seg.isProtected = true
                                seg.pendingProtection = false
                                seg.endTimeMs = finalizedFile.lastModified()
                                MediaScannerConnection.scanFile(
                                    this@RecordingService,
                                    arrayOf(destFile.absolutePath),
                                    null,
                                    null
                                )

                                traceLogger.logProtectionTrace(
                                    SegmentProtectionTraceEntry(
                                        segmentName = seg.segmentName,
                                        isProtected = true,
                                        pendingProtection = false,
                                        triggerEventType = "FINALIZE",
                                        protectionReason = ProtectionReason.PENDING_PROTECTION_FINALIZED,
                                        notificationSent = true,
                                        operationSucceeded = true
                                    )
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to copy finalized segment", e)
                                traceLogger.logProtectionTrace(
                                    SegmentProtectionTraceEntry(
                                        segmentName = seg.segmentName,
                                        isProtected = false,
                                        pendingProtection = true,
                                        triggerEventType = "FINALIZE",
                                        protectionReason = ProtectionReason.PENDING_PROTECTION_FINALIZED,
                                        notificationSent = false,
                                        operationSucceeded = false,
                                        failureReason = ProtectionFailureReason.FILE_COPY_FAILED
                                    )
                                )
                            }
                        }

                        val iterator = activeIncidentWindows.iterator()
                        val incidents =
                            IncidentLogStore.loadIncidents(this@RecordingService).toMutableList()

                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            val window = entry.value
                            if (window.targetSegmentPaths.any { File(it).absolutePath == finalizedFile.absolutePath }) {
                                if (finalizedFile.exists() && finalizedFile.length() > 0) {
                                    val recordingsDir =
                                        FileUtils.getRecordingsDir(this@RecordingService)
                                    val destFile = File(recordingsDir, finalizedFile.name)
                                    finalizedFile.copyTo(destFile, overwrite = true)
                                    MediaScannerConnection.scanFile(
                                        this@RecordingService,
                                        arrayOf(destFile.absolutePath),
                                        null,
                                        null
                                    )
                                    Log.i(
                                        TAG,
                                        "🔒 現行セグメント(書き込み中)のファイナライズ完了・保護コピー実行: ${destFile.absolutePath}"
                                    )
                                }

                                val inc = incidents.find { it.id == window.incidentId }
                                if (inc != null && inc.status == IncidentStatus.PENDING) {
                                    inc.status = IncidentStatus.SUCCESS
                                    if (finalizedFile.exists()) {
                                        inc.timestampMs = finalizedFile.lastModified()
                                    }

                                    val recordingsDir =
                                        FileUtils.getRecordingsDir(this@RecordingService)
                                    val destFile = File(recordingsDir, finalizedFile.name)
                                    val updatedPaths = inc.coveredSegmentFiles.map { oldPath ->
                                        val oldFile = File(oldPath)
                                        if (oldFile.name == finalizedFile.name) destFile.absolutePath else oldPath
                                    }.toMutableList()
                                    if (!updatedPaths.contains(destFile.absolutePath)) {
                                        updatedPaths.add(destFile.absolutePath)
                                    }
                                    inc.coveredSegmentFiles = updatedPaths
                                    if (inc.videoFilePath.isEmpty() || !File(inc.videoFilePath).exists()) {
                                        inc.videoFilePath = destFile.absolutePath
                                    }

                                    IncidentLogStore.saveIncidents(this@RecordingService, incidents)
                                    Log.i(
                                        TAG,
                                        "✨ PENDING → SUCCESS 状態遷移完了(現行セグメント完了時): ${inc.id}"
                                    )
                                    showRecordingCompletionNotification(inc)
                                    iterator.remove()
                                }
                            }
                        }

                        // フェイルセーフ: パスが空のインシデントがあれば、今回のファイナライズファイルで強制補完
                        val freshIncidents =
                            IncidentLogStore.loadIncidents(this@RecordingService).toMutableList()
                        val pendingIncidents =
                            freshIncidents.filter { it.status == IncidentStatus.PENDING && (it.videoFilePath.isEmpty() || it.coveredSegmentFiles.isEmpty()) }
                        if (pendingIncidents.isNotEmpty() && finalizedFile.exists() && finalizedFile.length() > 0) {
                            val recordingsDir = FileUtils.getRecordingsDir(this@RecordingService)
                            val destFile = File(recordingsDir, finalizedFile.name)
                            if (!destFile.exists()) {
                                finalizedFile.copyTo(destFile, overwrite = true)
                                MediaScannerConnection.scanFile(
                                    this@RecordingService,
                                    arrayOf(destFile.absolutePath),
                                    null,
                                    null
                                )
                            }

                            pendingIncidents.forEach { inc ->
                                if (inc.videoFilePath.isEmpty()) inc.videoFilePath =
                                    destFile.absolutePath
                                if (inc.coveredSegmentFiles.isEmpty()) inc.coveredSegmentFiles =
                                    mutableListOf(destFile.absolutePath)
                                inc.status = IncidentStatus.SUCCESS
                                Log.i(
                                    TAG,
                                    "🛡️ フェイルセーフ救済: パス空のインシデント ${inc.id} に ${destFile.absolutePath} を強制紐付けました"
                                )
                            }
                            IncidentLogStore.saveIncidents(this@RecordingService, freshIncidents)
                        }
                    }

                    if (_serviceState.value.isRecording) {
                        serviceScope.launch(Dispatchers.Main) {
                            startVideoFileOutput()
                        }
                    }
                }
            }
        }
    }

    private suspend fun stopRecordingPipeline() {
        Log.i(TAG, "🛑 録画パイプラインの安全停止処理を開始します")

        // 1. セグメント切替タイマーの停止
        stopSegmentRotationTimer()

        // 2. ImageAnalysis アナライザーの即時解除 (遅延フレーム解析タスクの遮断)
        try {
            imageAnalysis?.clearAnalyzer()
            Log.i(TAG, "🧹 imageAnalysis アナライザーを正常に解除しました")
        } catch (e: Exception) {
            Log.e(TAG, "clearAnalyzer 実行エラー", e)
        }

        // 3. 録画停止指示と VideoRecordEvent.Finalize 完了の待機 (タイムアウト 3000ms)
        val recording = activeRecording
        if (recording != null) {
            activeRecording = null
            try {
                val finalizedResult = withTimeoutOrNull(3000L) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        recordingStopContinuation = continuation
                        recording.stop()
                    }
                }
                if (finalizedResult == null) {
                    Log.w(
                        TAG,
                        "⚠️ 録画ファイルのファイナライズ (Finalized) 待機が 3000ms でタイムアウトしました。強制的にリソース解放へ移行します"
                    )
                } else {
                    Log.i(TAG, "🎬 録画ファイルのファイナライズ (Finalized) を確認しました")
                }
            } catch (e: Exception) {
                Log.e(TAG, "録画停止待機エラー", e)
            } finally {
                recordingStopContinuation = null
            }
        }

        // 4. Finalized 確認後の参照クリアおよび CameraProvider のアンバインド
        rearApproachDetector = null
        imageAnalysis = null

        try {
            cameraProvider?.unbindAll()
            videoCapture = null
            cameraProvider = null
            Log.i(TAG, "📷 cameraProvider を安全にアンバインドし、参照をクリアしました")
        } catch (e: Exception) {
            Log.e(TAG, "cameraProvider.unbindAll エラー", e)
        }

        // 5. WakeLock 解放
        releaseWakeLock()

        // 6. サービス状態更新
        _serviceState.value = _serviceState.value.copy(
            isRecording = false
        )
        traceLogger.endSession()
        Log.i(TAG, "✅ 録画パイプラインの安全停止処理が完了しました")
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showRecordingCompletionNotification(incidentLog: IncidentLog) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("EXTRA_TARGET_EVENT_ID", incidentLog.triggerDetectionLogId)
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, incidentLog.id.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChariSafe 記録セグメント保護完了")
            .setContentText("${incidentLog.triggerEventType.displayName}イベントの前後動画を保護しました")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(incidentLog.id.hashCode(), notification)
    }

    override fun onDestroy() {
        collisionDropDetector?.stopListening()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
