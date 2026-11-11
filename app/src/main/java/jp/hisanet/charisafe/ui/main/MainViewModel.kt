package jp.hisanet.charisafe.ui.main

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.hisanet.charisafe.data.config.ConfigRepositoryImpl
import jp.hisanet.charisafe.data.local.RideSummaryStore
import jp.hisanet.charisafe.data.repository.EventLogRepositoryImpl
import jp.hisanet.charisafe.data.repository.GpxRepositoryImpl
import jp.hisanet.charisafe.data.repository.RecordingRepositoryImpl
import jp.hisanet.charisafe.data.export.ZipExportService
import jp.hisanet.charisafe.domain.ride.LiveRideSummaryService
import jp.hisanet.charisafe.domain.ride.JsonlRideSummaryService
import jp.hisanet.charisafe.domain.shadowrun.GpxShadowRunService
import jp.hisanet.charisafe.data.debug.TraceLayer
import jp.hisanet.charisafe.data.debug.TraceLogger
import jp.hisanet.charisafe.data.debug.TraceMetric
import jp.hisanet.charisafe.domain.model.ActiveCamera
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.EventCountSummary
import jp.hisanet.charisafe.domain.model.EventDetectionLog
import jp.hisanet.charisafe.domain.model.EventSensitivity
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.GpxPoint
import jp.hisanet.charisafe.domain.model.IncidentLog
import jp.hisanet.charisafe.domain.model.RecordingState
import jp.hisanet.charisafe.domain.model.RideSummary
import jp.hisanet.charisafe.domain.model.SystemStatus
import jp.hisanet.charisafe.domain.model.VideoResolution
import jp.hisanet.charisafe.domain.repository.ConfigRepository
import jp.hisanet.charisafe.domain.repository.EventLogRepository
import jp.hisanet.charisafe.domain.repository.GpxRepository
import jp.hisanet.charisafe.domain.repository.RecordingRepository
import jp.hisanet.charisafe.ui.model.EventMetricUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.edit
import jp.hisanet.charisafe.data.repository.JsonlSessionRepository
import kotlin.time.Duration.Companion.milliseconds

enum class Screen {
    MAIN, HISTORY, RIDE_SUMMARY, SETTINGS
}

sealed class TimelineItem(open val timestampMs: Long) {
    data class Recording(val incidentLog: IncidentLog) : TimelineItem(incidentLog.timestampMs)
    data class Detection(val detectionLog: EventDetectionLog) : TimelineItem(detectionLog.timestampMs)
}

data class MainUiState(
    val config: AppConfig = AppConfig(),
    val status: SystemStatus = SystemStatus(),
    val isExporting: Boolean = false,
    val isZipGenerating: Boolean = false,
    val savedEventFiles: List<File> = emptyList(),
    val selectedEventFile: File? = null,
    val eventDetectionLogs: List<EventDetectionLog> = emptyList(),
    val incidentLogs: List<IncidentLog> = emptyList(),
    val groupedTimelineItems: Map<String, List<TimelineItem>> = emptyMap(),
    val rideSummaries: List<RideSummary> = emptyList(),
    val currentScreen: Screen = Screen.MAIN,
    val targetEventIdToHighlight: String? = null,
    val sessionEventCounts: EventCountSummary = EventCountSummary(),
    val eventCounts: EventCountSummary = EventCountSummary(),
    val sensitivity: EventSensitivity = EventSensitivity(),
    val currentRideSummary: RideSummary? = null,
    val userMessage: String? = null,
    val currentApproachScore: Float = 0f,
    val currentDecelValue: Float = 0f,
    val currentGValue: Float = 0f,
    val currentSoundAmplitude: Float = 0f,
    val eventMetricCards: List<EventMetricUiModel> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val configRepository: ConfigRepository = ConfigRepositoryImpl(application)
    private val recordingRepository: RecordingRepository = RecordingRepositoryImpl(application)
    private val gpxRepository: GpxRepository = GpxRepositoryImpl.getInstance(application)
    private val eventLogRepository: EventLogRepository = EventLogRepositoryImpl(application)
    private val uiStateDebugLogger = jp.hisanet.charisafe.data.debug.UiStateDebugLogger(application)
    private val zipExportService = ZipExportService(application)
    private val liveRideSummaryService = LiveRideSummaryService()
    private val jsonlRideSummaryService = JsonlRideSummaryService(application)
    private val gpxShadowRunService = GpxShadowRunService(application)

    private val prefs = application.getSharedPreferences("charisafe_app_config", android.content.Context.MODE_PRIVATE)
    private val _savedEventFiles = MutableStateFlow<List<File>>(emptyList())
    private val _currentScreen = MutableStateFlow(Screen.MAIN)
    private val _targetEventId = MutableStateFlow<String?>(null)
    private val _rideSummaries = MutableStateFlow<List<RideSummary>>(emptyList())
    private val _sessionStartTimeMs = MutableStateFlow(0L)

    private val _sensitivity = MutableStateFlow(
        EventSensitivity(
            decelerationSensitivity = prefs.getFloat("key_decel_sensitivity", 1.5f),
            approachSensitivity = prefs.getFloat("key_approach_sensitivity", 1.5f),
            collisionSensitivity = prefs.getFloat("key_collision_sensitivity", 3.0f),
            soundSensitivity = prefs.getFloat("key_sound_sensitivity", 0.6f)
        )
    )
    private val _currentRideSummary = MutableStateFlow<RideSummary?>(null)
    private val _isZipGenerating = MutableStateFlow(false)
    private var rideStartTimeMs: Long = 0L
    private val recordedPoints = mutableListOf<GpxPoint>()

    val uiState: StateFlow<MainUiState> = combine(
        combine(configRepository.configState, recordingRepository.recordingState, gpxRepository.currentGpxPoint) { cfg, state, gpx ->
            Triple(cfg, state, gpx)
        },
        combine(_savedEventFiles, eventLogRepository.eventDetectionLogs, eventLogRepository.incidentLogs) { files, detections, incidents ->
            Triple(files, detections, incidents)
        },
        combine(_currentScreen, _targetEventId, _sensitivity) { screen, targetId, sens ->
            Triple(screen, targetId, sens)
        },
        combine(_currentRideSummary, _rideSummaries, _sessionStartTimeMs, _isZipGenerating) { currentSummary, summaries, sessionStart, isZipGen ->
            listOf(currentSummary, summaries, sessionStart, isZipGen)
        },
        combine(recordingRepository.approachScoreState, gpxRepository.decelerationState, recordingRepository.gForceState, recordingRepository.soundAmplitudeState) { app, dec, g, snd ->
            listOf(app, dec, g, snd)
        }
    ) { p1, p2, p3, p4, pLive ->
        val config = p1.first
        val state = p1.second
        val gpxPoint = p1.third

        val files = p2.first
        val detections = p2.second
        val incidents = p2.third

        val screen = p3.first
        val targetId = p3.second
        val sensitivity = p3.third

        @Suppress("UNCHECKED_CAST")
        val currentSummary = p4[0] as? RideSummary
        val summaries = p4[1] as? List<RideSummary> ?: emptyList()
        val sessionStart = p4[2] as Long
        val isZipGen = p4[3] as Boolean

        val approachVal = pLive[0]
        val decelVal = pLive[1]
        val gVal = pLive[2]
        val soundVal = pLive[3]

        val status = SystemStatus(
            state = state,
            currentSpeedKmh = gpxPoint?.speedKmh ?: 0f,
            sessionStartTimeMs = sessionStart,
            activeGpxFile = gpxRepository.getActiveGpxFilePath(),
            lastEventFile = incidents.firstOrNull()?.coveredSegmentFiles?.firstOrNull() ?: files.firstOrNull()?.absolutePath
        )

        // 累計件数
        val totalCounts = EventCountSummary(
            approachCount = detections.count { it.eventType == EventType.APPROACH },
            decelerationCount = detections.count { it.eventType == EventType.DECELERATION },
            collisionCount = detections.count { it.eventType == EventType.COLLISION },
            soundCount = detections.count { it.eventType == EventType.SOUND }
        )

        // タイムライン構築＆日付グルーピング (降順) - 完全な動画中心モデルとして Recording のみを対象とする
        val timeline = mutableListOf<TimelineItem>()
        incidents.forEach { timeline.add(TimelineItem.Recording(it)) }
        val sortedTimeline = timeline.sortedByDescending { it.timestampMs }

        val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val grouped = sortedTimeline.groupBy { dateFormatter.format(Date(it.timestampMs)) }

        val pointsCopy = synchronized(recordedPoints) { recordedPoints.toList() }
        val liveSummary = liveRideSummaryService.calculateLiveSummary(
            recordedPoints = pointsCopy,
            detections = detections,
            sessionStartMs = sessionStart,
            rideStartMs = rideStartTimeMs
        )

        uiStateDebugLogger.log(
            approachScore = approachVal,
            decelVal = decelVal,
            gVal = gVal,
            soundVal = soundVal,
            speedKmh = status.currentSpeedKmh
        )
        TraceLogger.getInstance(getApplication()).log(TraceMetric.APPROACH, TraceLayer.VIEWMODEL, approachVal)
        TraceLogger.getInstance(getApplication()).log(TraceMetric.DECELERATION, TraceLayer.VIEWMODEL, decelVal)
        TraceLogger.getInstance(getApplication()).log(TraceMetric.COLLISION, TraceLayer.VIEWMODEL, gVal)
        TraceLogger.getInstance(getApplication()).log(TraceMetric.SOUND, TraceLayer.VIEWMODEL, soundVal)
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i("AudioStatTrace", "$timeStr\n6. VmCurrentSoundAmplitude=$soundVal")

        val metricCards = listOf(
            EventMetricUiModel(
                eventType = EventType.APPROACH,
                currentValue = approachVal,
                thresholdValue = sensitivity.approachSensitivity,
                eventCount = totalCounts.getCount(EventType.APPROACH)
            ),
            EventMetricUiModel(
                eventType = EventType.DECELERATION,
                currentValue = decelVal,
                thresholdValue = sensitivity.decelerationSensitivity,
                eventCount = totalCounts.getCount(EventType.DECELERATION)
            ),
            EventMetricUiModel(
                eventType = EventType.COLLISION,
                currentValue = gVal,
                thresholdValue = sensitivity.collisionSensitivity,
                eventCount = totalCounts.getCount(EventType.COLLISION)
            ),
            EventMetricUiModel(
                eventType = EventType.SOUND,
                currentValue = soundVal,
                thresholdValue = sensitivity.soundSensitivity,
                eventCount = totalCounts.getCount(EventType.SOUND)
            )
        )

        MainUiState(
            config = config,
            status = status,
            isExporting = (state == RecordingState.EXPORTING),
            isZipGenerating = isZipGen,
            savedEventFiles = files,
            eventDetectionLogs = detections,
            incidentLogs = incidents,
            groupedTimelineItems = grouped,
            rideSummaries = summaries,
            currentScreen = screen,
            targetEventIdToHighlight = targetId,
            sessionEventCounts = totalCounts,
            eventCounts = totalCounts,
            sensitivity = sensitivity,
            currentRideSummary = currentSummary,
            currentApproachScore = approachVal,
            currentDecelValue = decelVal,
            currentGValue = gVal,
            currentSoundAmplitude = soundVal,
            eventMetricCards = metricCards
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    init {
        loadExistingEvents()
        syncAndRecalculateRideSummaries()

        // 定期的にイベント履歴およびインシデントを自動リロードしてUIにリアルタイム反映
        viewModelScope.launch {
            while (true) {
                delay(2000L.milliseconds)
                loadExistingEvents()
            }
        }

        viewModelScope.launch {
            gpxRepository.currentGpxPoint.collect { point ->
                if (point != null && uiState.value.status.state == RecordingState.RECORDING) {
                    synchronized(recordedPoints) {
                        recordedPoints.add(point)
                    }
                    updateLiveRideSummary()
                }
            }
        }
    }

    fun setScreen(screen: Screen) {
        _currentScreen.value = screen
        if (screen == Screen.RIDE_SUMMARY) {
            syncAndRecalculateRideSummaries()
        }
    }

    private fun syncAndRecalculateRideSummaries() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val jsonlFiles = jsonlRideSummaryService.getJsonlFiles()

                if (jsonlFiles.isNotEmpty()) {
                    gpxShadowRunService.runShadowRun(jsonlFiles)

                    val cumulativeSummary = jsonlRideSummaryService.calculateCumulativeSummary()
                    if (cumulativeSummary != null) {
                        TraceLogger.getInstance(context).log(TraceMetric.RECORDING_STATE, TraceLayer.SYSTEM, cumulativeSummary.totalDistanceKm.toFloat(), "CUMULATIVE_RIDE_SUMMARY_CALCULATED_JSONL", force = true)
                        withContext(Dispatchers.Main) {
                            _currentRideSummary.value = cumulativeSummary
                            _rideSummaries.value = listOf(cumulativeSummary)
                        }
                        return@launch
                    }
                }

                // Phase 3 SSOT policy: No GPX fallback. If JSONL is empty/absent, summary is empty.
                withContext(Dispatchers.Main) {
                    _currentRideSummary.value = null
                    _rideSummaries.value = emptyList()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to calculate cumulative ride summary", e)
                withContext(Dispatchers.Main) {
                    _currentRideSummary.value = null
                    _rideSummaries.value = emptyList()
                }
            }
        }
    }

    fun setTargetEventIdToHighlight(eventId: String?) {
        _targetEventId.value = eventId
        if (eventId != null) {
            _currentScreen.value = Screen.HISTORY
        }
    }

    fun loadExistingEvents() {
        viewModelScope.launch {
            try {
                eventLogRepository.reload()
                val recordingsDir = jp.hisanet.charisafe.util.FileUtils.getRecordingsDir(getApplication())
                val files = recordingsDir.listFiles { _, name -> name.startsWith("event_") && name.endsWith(".mp4") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
                _savedEventFiles.value = files
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load existing events", e)
            }
        }
    }

    fun loadRideSummaries() {
        viewModelScope.launch {
            val summaries = RideSummaryStore.loadSummaries(getApplication())
            _rideSummaries.value = summaries
        }
    }

    fun startRecording(context: Context) {
        val config = configRepository.getConfig()
        recordingRepository.startRecording(context, config)
        rideStartTimeMs = System.currentTimeMillis()
        _sessionStartTimeMs.value = rideStartTimeMs
        synchronized(recordedPoints) {
            recordedPoints.clear()
        }
        updateLiveRideSummary()
    }

    fun stopRecording() {
        recordingRepository.stopRecording()

        _sessionStartTimeMs.value = 0L
        rideStartTimeMs = 0L

        syncAndRecalculateRideSummaries()
        loadExistingEvents()
        Log.i(TAG, "Ride ended. Cumulative summary recalculated.")
    }

    fun updateActiveCamera(activeCamera: ActiveCamera) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(activeCamera = activeCamera))
    }

    fun updateFps(fps: Int) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(fps = fps))
    }

    fun updateGpsInterval(seconds: Long) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(gpsIntervalSeconds = seconds))
    }

    fun updateApproachGridCount(count: Int) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(approachGridCount = count))
    }

    fun updateSensitivity(sensitivity: EventSensitivity) {
        _sensitivity.value = sensitivity
        prefs.edit {
            putFloat("key_decel_sensitivity", sensitivity.decelerationSensitivity)
                .putFloat("key_approach_sensitivity", sensitivity.approachSensitivity)
                .putFloat("key_collision_sensitivity", sensitivity.collisionSensitivity)
                .putFloat("key_sound_sensitivity", sensitivity.soundSensitivity)
        }
    }

    fun deleteCurrentRide() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()

                // 1. 検出ログ・インシデントログのクリア
                eventLogRepository.clearAll()

                // 2. メモリ上の GPX ポイントのクリア
                gpxRepository.clearAll()
                synchronized(recordedPoints) {
                    recordedPoints.clear()
                }
                rideStartTimeMs = 0L
                _sessionStartTimeMs.value = 0L
                _currentRideSummary.value = null

                // 3. recordings フォルダの全削除 ＆ 再作成
                val recordingsDir = jp.hisanet.charisafe.util.FileUtils.getRecordingsDir(context)
                if (recordingsDir.exists()) {
                    recordingsDir.deleteRecursively()
                }
                recordingsDir.mkdirs()

                // 4. ring_buffer フォルダの全削除 ＆ 再作成
                val ringBufferDir = jp.hisanet.charisafe.util.FileUtils.getRingBufferDir(context)
                if (ringBufferDir.exists()) {
                    ringBufferDir.deleteRecursively()
                }
                ringBufferDir.mkdirs()

                // 5. gpx フォルダの全削除 ＆ 再作成
                val gpxDir = jp.hisanet.charisafe.util.FileUtils.getGpxDir(context)
                if (gpxDir.exists()) {
                    gpxDir.deleteRecursively()
                }
                gpxDir.mkdirs()

                // 5.5 logs フォルダ（JSONLセッションログ）の全削除 ＆ 再作成
                val logsDir = jp.hisanet.charisafe.util.FileUtils.getLogsDir(context)
                if (logsDir.exists()) {
                    logsDir.deleteRecursively()
                }
                logsDir.mkdirs()

                // 6. debug フォルダ（CSV等）の全削除 ＆ 再作成
                val debugDir = context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug")
                if (debugDir.exists()) {
                    debugDir.deleteRecursively()
                }
                debugDir.mkdirs()

                // 7. キャッシュ内の一時ZIPファイルの削除
                context.cacheDir.listFiles { _, name -> name.endsWith(".zip") }?.forEach { it.delete() }

                withContext(Dispatchers.Main) {
                    loadExistingEvents()
                    loadRideSummaries()
                    android.widget.Toast.makeText(context, "🗑️ 現在ライドのデータをすべて削除しました（設定は維持されます）", android.widget.Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Current ride data completely cleaned up. Settings retained.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to completely delete current ride data", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "削除失敗: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateLiveRideSummary() {
        val pointsCopy = synchronized(recordedPoints) { recordedPoints.toList() }
        val detections = eventLogRepository.eventDetectionLogs.value
        val summary = liveRideSummaryService.calculateLiveSummary(
            recordedPoints = pointsCopy,
            detections = detections,
            sessionStartMs = _sessionStartTimeMs.value,
            rideStartMs = rideStartTimeMs
        )
        _currentRideSummary.value = summary
    }

    fun updateResolution(resolution: VideoResolution) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(resolution = resolution))
    }

    fun shareAllData() {
        zipExportService.shareAllData { _isZipGenerating.value = it }
    }

    fun shareLogsOnly() {
        zipExportService.shareLogsOnly { _isZipGenerating.value = it }
    }

    fun shareVideosOnly() {
        zipExportService.shareVideosOnly { _isZipGenerating.value = it }
    }
}
