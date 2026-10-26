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
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.ToneGenerator
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
import jp.hisanet.charisafe.data.config.ConfigRepositoryImpl
import jp.hisanet.charisafe.data.debug.ApproachDebugLogger
import jp.hisanet.charisafe.data.debug.AudioDebugLogger
import jp.hisanet.charisafe.data.debug.CollisionDebugLogger
import jp.hisanet.charisafe.data.debug.DebugLogCleanupManager
import jp.hisanet.charisafe.data.local.EventDetectionLogStore
import jp.hisanet.charisafe.data.local.IncidentLogStore
import jp.hisanet.charisafe.data.local.ringbuffer.RingBufferManager
import jp.hisanet.charisafe.data.location.LocationTracker
import jp.hisanet.charisafe.data.sensor.AudioImpactDetector
import jp.hisanet.charisafe.data.sensor.CollisionDropDetector
import jp.hisanet.charisafe.data.sensor.PocketStorageDetector
import jp.hisanet.charisafe.domain.model.*
import jp.hisanet.charisafe.domain.repository.ConfigRepository
import jp.hisanet.charisafe.poc.PocMetricsLogger
import jp.hisanet.charisafe.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private var pocketStorageDetector: PocketStorageDetector? = null
    private var collisionDropDetector: CollisionDropDetector? = null
    private var audioImpactDetector: AudioImpactDetector? = null
    private var approachDebugLogger: ApproachDebugLogger? = null
    private var audioDebugLogger: AudioDebugLogger? = null
    private var collisionDebugLogger: CollisionDebugLogger? = null
    private var recordingStartTimeMs: Long = 0L

    private val eventCoordinator = EventCoordinator()
    private val configRepository: ConfigRepository by lazy { ConfigRepositoryImpl(this) }

    private var activeCamera: ActiveCamera = ActiveCamera.REAR
    private var ringBufferMinutes: Int = 5
    private var targetFps: Int = 10
    private var userConfigFps: Int = 10
    private var resWidth: Int = 1280
    private var resHeight: Int = 720
    private var audioEnabled: Boolean = true

    private var metricsLogger: PocMetricsLogger? = null
    private var metricsJob: Job? = null
    private var rotationJob: Job? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private val _serviceState = MutableStateFlow(ServiceState())

    private data class IncidentWindow(
        val incidentId: String,
        val targetSegmentPaths: MutableSet<String>,
        val relatedDetectionIds: MutableList<String>,
        var highestSeverityType: EventType
    )

    private val activeIncidentWindows = java.util.Collections.synchronizedMap(mutableMapOf<String, IncidentWindow>())

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override val lifecycle: Lifecycle = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        ringBufferManager = RingBufferManager(this, ringBufferMinutes)
        createNotificationChannel()
        DebugLogCleanupManager.cleanOldLogs(this)
        approachDebugLogger = ApproachDebugLogger(this)
        audioDebugLogger = AudioDebugLogger(this)
        collisionDebugLogger = CollisionDebugLogger(this)

        pocketStorageDetector = PocketStorageDetector(this) { isStored ->
            if (isStored) {
                Log.w(TAG, "👝 [Priority3] ポケット収納検知: 省電力モードのためFPSを5fpsにスロットリングします")
                targetFps = 5
            } else {
                Log.i(TAG, "☀️ [Priority3] ポケットから取り出されました: 通常FPS ($userConfigFps fps) に復帰します")
                targetFps = userConfigFps
            }
        }

        collisionDropDetector = CollisionDropDetector(this, collisionDebugLogger) {
            val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
            val sens = prefs.getFloat("key_collision_sensitivity", 3.0f)
            handleDetectedEvent(EventType.COLLISION, sens, "G: ${String.format(Locale.US, "%.1f", sens)}G")
        }

        audioImpactDetector = AudioImpactDetector(
            audioDebugLogger = audioDebugLogger,
            getSoundSensitivityAmplitude = {
                val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
                prefs.getFloat("key_sound_sensitivity", 5000.0f)
            }
        ) {
            val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
            val sens = prefs.getFloat("key_sound_sensitivity", 5000.0f)
            handleDetectedEvent(EventType.SOUND, sens, "振幅: ${sens.toInt()}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            Log.w(TAG, "onStartCommand received null action, ignoring to prevent redelivery bugs")
            return START_NOT_STICKY
        }

        // 設定リポジトリの最新値を正としてカメラ選択を確実に同期
        activeCamera = configRepository.getConfig().activeCamera
        ringBufferMinutes = intent.getIntExtra(EXTRA_RING_BUFFER_MINUTES, 5)
        targetFps = intent.getIntExtra(EXTRA_TARGET_FPS, 10)
        userConfigFps = targetFps
        resWidth = intent.getIntExtra(EXTRA_RESOLUTION_WIDTH, 1280)
        resHeight = intent.getIntExtra(EXTRA_RESOLUTION_HEIGHT, 720)
        audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, true)

        ringBufferManager = RingBufferManager(this, ringBufferMinutes)

        Log.i(TAG, "onStartCommand action: $action, camera: $activeCamera, ringBufferMinutes: $ringBufferMinutes")

        when (action) {
            ACTION_START_RECORDING -> {
                recordingStartTimeMs = System.currentTimeMillis()
                startForegroundWithNotification()
                acquireWakeLock()
                pocketStorageDetector?.startListening()
                collisionDropDetector?.startListening()
                audioImpactDetector?.startListening()
                startRecordingPipeline()
                startSegmentRotationTimer()
            }
            ACTION_STOP_RECORDING -> {
                pocketStorageDetector?.stopListening()
                collisionDropDetector?.stopListening()
                audioImpactDetector?.stopListening()
                stopRecordingPipeline()
                stopForegroundAndSelf()
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
                delay(60_000L) // 60秒 (1分) ごとにセグメント切替
                if (_serviceState.value.isRecording) {
                    Log.i(TAG, "⏱️ 1分経過したため、リングバッファセグメントを自動ローテーション（切替）します")
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

        val recorderBuilder = Recorder.Builder()
        val qualitySelector = QualitySelector.from(Quality.HD)
        recorderBuilder.setQualitySelector(qualitySelector)
        val recorder = recorderBuilder.build()

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

        analysis.setAnalyzer(analysisExecutor, RearApproachDetector {
            val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
            val sens = prefs.getFloat("key_approach_sensitivity", 0.7f)
            handleDetectedEvent(EventType.APPROACH, sens, "感度: ${(sens * 100).toInt()}%")
        })

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
            Log.i(TAG, "Bound CameraX VideoCapture + Analyzer ($activeCamera) at targetFps=$targetFps")
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
            EventType.DECELERATION -> prefs.getFloat("key_decel_sensitivity", 1.5f)
            EventType.APPROACH -> prefs.getFloat("key_approach_sensitivity", 0.7f)
            EventType.COLLISION -> prefs.getFloat("key_collision_sensitivity", 3.0f)
            EventType.SOUND -> prefs.getFloat("key_sound_sensitivity", 5000.0f)
        }
        val label = when (eventType) {
            EventType.DECELERATION -> "減速: ${sens}秒"
            EventType.APPROACH -> "感度: ${(sens * 100).toInt()}%"
            EventType.COLLISION -> "G: ${sens}G"
            EventType.SOUND -> "振幅: ${sens.toInt()}"
        }
        handleDetectedEvent(eventType, sens, label)
    }

    fun handleDetectedEvent(eventType: EventType, sensitivityValue: Float, sensitivityLabel: String) {
        val now = System.currentTimeMillis()
        if (now - recordingStartTimeMs < 3000L) return

        val lat = LocationTracker.lastValidLatitude
        val lon = LocationTracker.lastValidLongitude
        val speedKmh = LocationTracker.lastValidSpeedKmh
        val prefs = getSharedPreferences("charisafe_app_config", MODE_PRIVATE)
        val snapshot = EventSensitivity(
            decelerationSensitivity = prefs.getFloat("key_decel_sensitivity", 1.5f),
            approachSensitivity = prefs.getFloat("key_approach_sensitivity", 0.7f),
            collisionSensitivity = prefs.getFloat("key_collision_sensitivity", 3.0f),
            soundSensitivity = prefs.getFloat("key_sound_sensitivity", 5000.0f)
        )

        val detectionLog = EventDetectionLog(
            timestampMs = now,
            eventType = eventType,
            speedKmh = speedKmh,
            latitude = lat,
            longitude = lon,
            sensitivityValue = sensitivityValue,
            sensitivityLabel = sensitivityLabel,
            sensitivitySnapshot = snapshot
        )

        val existingDetections = EventDetectionLogStore.loadLogs(this).toMutableList()
        existingDetections.add(0, detectionLog)
        EventDetectionLogStore.saveLogs(this, existingDetections)

        serviceScope.launch(Dispatchers.IO) {
            try {
                if (eventType == EventType.APPROACH) {
                    audioImpactDetector?.isMuted = true
                    toneGenerator.startTone(ToneGenerator.TONE_DTMF_S, 800)
                    Log.i(TAG, "🔊 接近警告音鳴動 (音量検知一時ミュート開始)")
                    Thread.sleep(800L)
                    audioImpactDetector?.isMuted = false
                    Log.i(TAG, "🔊 接近警告音終了 (音量検知ミュート解除)")
                } else {
                    Log.i(TAG, "ℹ️ ${eventType.displayName}検知: 警告音なしでセグメント保護を実行します")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle alert tone / mute", e)
                audioImpactDetector?.isMuted = false
            }

            val currentFilePath = _serviceState.value.currentFile
            // 1. すでに書き込みが完了している過去セグメントのパスリストを取得して即時保護
            val completedPaths = ringBufferManager?.protectSegmentsForEvent(currentFilePath) ?: emptyList()
            val coveredFiles = mutableListOf<String>()
            coveredFiles.addAll(completedPaths)

            // 2. 現行セグメント（書き込み中）を保護予約対象として追加
            if (currentFilePath.isNotEmpty()) {
                val curFile = File(currentFilePath)
                if (curFile.exists()) {
                    coveredFiles.add(curFile.absolutePath)
                }
            }
            val candidateSet = coveredFiles.toSet()

            val existingIncidents = IncidentLogStore.loadIncidents(this@RecordingService).toMutableList()

            // インシデント統合判定
            var targetWindow: IncidentWindow? = null
            var targetIncident: IncidentLog? = null

            for ((_, window) in activeIncidentWindows) {
                if (window.targetSegmentPaths.any { candidateSet.contains(it) } || candidateSet.any { window.targetSegmentPaths.contains(it) }) {
                    targetWindow = window
                    targetIncident = existingIncidents.find { it.id == window.incidentId && it.status == IncidentStatus.PENDING }
                    break
                }
            }

            if (targetWindow != null && targetIncident != null) {
                targetWindow.relatedDetectionIds.add(detectionLog.id)
                targetWindow.highestSeverityType = getHigherSeverity(targetWindow.highestSeverityType, eventType)
                targetWindow.targetSegmentPaths.addAll(candidateSet)

                targetIncident.relatedDetectionIds = targetWindow.relatedDetectionIds.toList()
                targetIncident.highestSeverityType = targetWindow.highestSeverityType
                targetIncident.coveredSegmentFiles = targetWindow.targetSegmentPaths.toList()

                detectionLog.incidentId = targetIncident.id
                val updatedDetections = EventDetectionLogStore.loadLogs(this@RecordingService).toMutableList()
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
                    coveredSegmentFiles = coveredFiles,
                    status = IncidentStatus.PENDING
                )

                detectionLog.incidentId = newIncident.id
                val updatedDetections = EventDetectionLogStore.loadLogs(this@RecordingService).toMutableList()
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

            // 【即時通知判定】前セグメント（すでに書き込み完了しているファイル）が含まれている場合は即時通知発報
            if (completedPaths.isNotEmpty()) {
                val lastPath = completedPaths.last()
                val incToNotify = existingIncidents.find { it.id == targetIncident?.id ?: activeIncidentWindows.values.firstOrNull { w -> w.targetSegmentPaths.contains(lastPath) }?.incidentId }
                if (incToNotify != null) {
                    showRecordingCompletionNotification(incToNotify)
                    Log.i(TAG, "🔔 前セグメント即時保護のため、即時通知を発行しました: ${incToNotify.id}")
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
                    Log.i(TAG, "Segment recording finalized with error: ${event.error}")
                    _serviceState.value = _serviceState.value.copy(
                        isRecording = false,
                        errorMessage = "録画エラー発生: code=${event.error}"
                    )
                } else {
                    Log.i(TAG, "Segment recording finalized successfully: $filePath")

                    serviceScope.launch(Dispatchers.IO) {
                        val finalizedFile = File(filePath)
                        val iterator = activeIncidentWindows.iterator()
                        val incidents = IncidentLogStore.loadIncidents(this@RecordingService).toMutableList()

                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            val window = entry.value
                            if (window.targetSegmentPaths.any { File(it).absolutePath == finalizedFile.absolutePath }) {
                                if (finalizedFile.exists() && finalizedFile.length() > 0) {
                                    val recordingsDir = FileUtils.getRecordingsDir(this@RecordingService)
                                    val destFile = File(recordingsDir, finalizedFile.name)
                                    finalizedFile.copyTo(destFile, overwrite = true)
                                    MediaScannerConnection.scanFile(this@RecordingService, arrayOf(destFile.absolutePath), null, null)
                                    Log.i(TAG, "🔒 現行セグメント(書き込み中)のファイナライズ完了・保護コピー実行: ${destFile.absolutePath}")
                                }

                                val inc = incidents.find { it.id == window.incidentId }
                                if (inc != null && inc.status == IncidentStatus.PENDING) {
                                    inc.status = IncidentStatus.SUCCESS
                                    if (finalizedFile.exists()) {
                                        inc.timestampMs = finalizedFile.lastModified()
                                    }
                                    IncidentLogStore.saveIncidents(this@RecordingService, incidents)
                                    Log.i(TAG, "✨ PENDING → SUCCESS 状態遷移完了(現行セグメント完了時): ${inc.id}")
                                    // 現行セグメント完了時の遅延通知発報
                                    showRecordingCompletionNotification(inc)
                                    iterator.remove()
                                }
                            }
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
        stopSegmentRotationTimer()

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

    private fun showRecordingCompletionNotification(incidentLog: IncidentLog) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("EXTRA_TARGET_EVENT_ID", incidentLog.triggerDetectionLogId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        pocketStorageDetector?.stopListening()
        collisionDropDetector?.stopListening()
        audioImpactDetector?.stopListening()
        analysisExecutor.shutdown()
        toneGenerator.release()
        super.onDestroy()
    }
}
