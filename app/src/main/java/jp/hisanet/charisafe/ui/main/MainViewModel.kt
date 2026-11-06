package jp.hisanet.charisafe.ui.main

import android.app.Application
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
import jp.hisanet.charisafe.data.debug.DecelerationDebugLogger
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
    private val decelerationDebugLogger = DecelerationDebugLogger(application)
    private val gpxRepository: GpxRepository = GpxRepositoryImpl(application, decelerationDebugLogger)
    private val eventLogRepository: EventLogRepository = EventLogRepositoryImpl(application)
    private val uiStateDebugLogger = jp.hisanet.charisafe.data.debug.UiStateDebugLogger(application)

    private val prefs = application.getSharedPreferences("charisafe_app_config", android.content.Context.MODE_PRIVATE)
    private val _savedEventFiles = MutableStateFlow<List<File>>(emptyList())
    private val _currentScreen = MutableStateFlow(Screen.MAIN)
    private val _targetEventId = MutableStateFlow<String?>(null)
    private val _rideSummaries = MutableStateFlow<List<RideSummary>>(emptyList())
    private val _sessionStartTimeMs = MutableStateFlow<Long>(0L)

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
        combine(_currentRideSummary, _rideSummaries, _sessionStartTimeMs, _isZipGenerating) { _, summaries, sessionStart, isZipGen ->
            listOf(null, summaries, sessionStart, isZipGen)
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

        val currentSum = p4[0] as? RideSummary
        @Suppress("UNCHECKED_CAST")
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

        val activeStart = if (sessionStart > 0L) sessionStart else (rideStartTimeMs.takeIf { it > 0L } ?: System.currentTimeMillis())
        val totalActiveSec = if (sessionStart > 0L || rideStartTimeMs > 0L) ((System.currentTimeMillis() - activeStart) / 1000L).coerceAtLeast(0L) else 0L
        val hours = if (totalActiveSec > 0) totalActiveSec / 3600.0 else 1.0
        val pointsCopy = synchronized(recordedPoints) { recordedPoints.toList() }
        var totalDistKm = 0.0
        var maxSpd = 0f
        var speedSum = 0f
        var speedCount = 0
        var movingSec = 0L
        var stoppedSec = 0L

        for (i in 1 until pointsCopy.size) {
            val p1 = pointsCopy[i - 1]
            val p2 = pointsCopy[i]
            val dist = calculateHaversineDistance(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            totalDistKm += dist
            if (p2.speedKmh > maxSpd) maxSpd = p2.speedKmh
            speedSum += p2.speedKmh
            speedCount++

            val dt = (p2.timestampMs - p1.timestampMs) / 1000L
            if (dt in 1..60) {
                if (p2.speedKmh >= 1.0f) {
                    movingSec += dt
                } else {
                    stoppedSec += dt
                }
            }
        }
        val avgSpd = if (speedCount > 0) speedSum / speedCount else 0f
        if (movingSec == 0L && stoppedSec == 0L && totalActiveSec > 0L) {
            movingSec = totalActiveSec
        }

        val liveSummary = RideSummary(
            startTimeMs = activeStart,
            endTimeMs = System.currentTimeMillis(),
            movingTimeSec = movingSec,
            stoppedTimeSec = stoppedSec,
            totalDistanceKm = totalDistKm,
            averageSpeedKmh = avgSpd,
            maxSpeedKmh = maxSpd,
            eventCounts = totalCounts,
            decelerationPerKm = if (totalDistKm > 0.0) totalCounts.decelerationCount.toFloat() / totalDistKm.toFloat() else 0f,
            decelerationPerHour = (totalCounts.decelerationCount.toFloat() / hours).toFloat(),
            approachPerKm = if (totalDistKm > 0.0) totalCounts.approachCount.toFloat() / totalDistKm.toFloat() else 0f,
            approachPerHour = (totalCounts.approachCount.toFloat() / hours).toFloat(),
            collisionPerKm = if (totalDistKm > 0.0) totalCounts.collisionCount.toFloat() / totalDistKm.toFloat() else 0f,
            collisionPerHour = (totalCounts.collisionCount.toFloat() / hours).toFloat(),
            soundPerKm = if (totalDistKm > 0.0) totalCounts.soundCount.toFloat() / totalDistKm.toFloat() else 0f,
            soundPerHour = (totalCounts.soundCount.toFloat() / hours).toFloat()
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
            currentRideSummary = if ((liveSummary?.totalDistanceKm ?: 0.0) > 0.0) liveSummary else summaries.firstOrNull(),
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
                val gpxDir = jp.hisanet.charisafe.util.FileUtils.getGpxDir(context)
                if (!gpxDir.exists()) {
                    withContext(Dispatchers.Main) {
                        _currentRideSummary.value = null
                        _rideSummaries.value = emptyList()
                    }
                    return@launch
                }

                val gpxFiles = gpxDir.listFiles { _, name -> name.endsWith(".gpx") }?.sortedBy { it.lastModified() } ?: return@launch
                if (gpxFiles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _currentRideSummary.value = null
                        _rideSummaries.value = emptyList()
                    }
                    return@launch
                }

                val allPoints = mutableListOf<GpxPoint>()
                for (file in gpxFiles) {
                    val points = readGpxPoints(file)
                    allPoints.addAll(points)
                }
                if (allPoints.isEmpty()) return@launch

                allPoints.sortBy { it.timestampMs }

                val startTimeMs = allPoints.first().timestampMs
                val endTimeMs = allPoints.last().timestampMs

                var totalDistKm = 0.0
                var maxSpd = 0f
                var speedSum = 0f
                var speedCount = 0
                var movingSec = 0L
                var stoppedSec = 0L

                for (i in 1 until allPoints.size) {
                    val p1 = allPoints[i - 1]
                    val p2 = allPoints[i]
                    val dist = calculateHaversineDistance(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
                    totalDistKm += dist
                    if (p2.speedKmh > maxSpd) maxSpd = p2.speedKmh
                    speedSum += p2.speedKmh
                    speedCount++

                    val dt = (p2.timestampMs - p1.timestampMs) / 1000L
                    if (dt in 1..60) {
                        if (p2.speedKmh >= 1.0f) {
                            movingSec += dt
                        } else {
                            stoppedSec += dt
                        }
                    }
                }

                val avgSpd = if (speedCount > 0) speedSum / speedCount else 0f
                val totalDurationSec = if (startTimeMs > 0) (endTimeMs - startTimeMs) / 1000L else 0L
                if (movingSec == 0L && stoppedSec == 0L && totalDurationSec > 0L) {
                    movingSec = totalDurationSec
                }
                val hours = if (totalDurationSec > 0) totalDurationSec / 3600.0 else 1.0

                val detections = jp.hisanet.charisafe.data.local.EventDetectionLogStore.loadLogs(context)
                val counts = EventCountSummary(
                    approachCount = detections.count { it.eventType == EventType.APPROACH },
                    decelerationCount = detections.count { it.eventType == EventType.DECELERATION },
                    collisionCount = detections.count { it.eventType == EventType.COLLISION },
                    soundCount = detections.count { it.eventType == EventType.SOUND }
                )

                val cumulativeSummary = RideSummary(
                    id = "cumulative_ride_summary",
                    gpxFileName = "all_rides_accumulated",
                    isCompleted = true,
                    lastAggregatedTimestampMs = endTimeMs,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    movingTimeSec = movingSec,
                    stoppedTimeSec = stoppedSec,
                    totalDistanceKm = totalDistKm,
                    averageSpeedKmh = avgSpd,
                    maxSpeedKmh = maxSpd,
                    eventCounts = counts,
                    decelerationPerKm = if (totalDistKm > 0.0) counts.decelerationCount.toFloat() / totalDistKm.toFloat() else 0f,
                    decelerationPerHour = (counts.decelerationCount.toFloat() / hours).toFloat(),
                    approachPerKm = if (totalDistKm > 0.0) counts.approachCount.toFloat() / totalDistKm.toFloat() else 0f,
                    approachPerHour = (counts.approachCount.toFloat() / hours).toFloat(),
                    collisionPerKm = if (totalDistKm > 0.0) counts.collisionCount.toFloat() / totalDistKm.toFloat() else 0f,
                    collisionPerHour = (counts.collisionCount.toFloat() / hours).toFloat(),
                    soundPerKm = if (totalDistKm > 0.0) counts.soundCount.toFloat() / totalDistKm.toFloat() else 0f,
                    soundPerHour = (counts.soundCount.toFloat() / hours).toFloat()
                )

                TraceLogger.getInstance(context).log(TraceMetric.RECORDING_STATE, TraceLayer.SYSTEM, totalDistKm.toFloat(), "CUMULATIVE_RIDE_SUMMARY_CALCULATED", force = true)

                withContext(Dispatchers.Main) {
                    _currentRideSummary.value = cumulativeSummary
                    _rideSummaries.value = listOf(cumulativeSummary)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to calculate cumulative ride summary", e)
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

    fun startRecording() {
        val config = configRepository.getConfig()
        recordingRepository.startRecording(config)
        gpxRepository.startLogging(config.gpsIntervalSeconds)
        rideStartTimeMs = System.currentTimeMillis()
        _sessionStartTimeMs.value = rideStartTimeMs
    }

    fun stopRecording() {
        recordingRepository.stopRecording()
        gpxRepository.stopLogging()

        _sessionStartTimeMs.value = 0L
        rideStartTimeMs = 0L

        syncAndRecalculateRideSummaries()
        loadExistingEvents()
        Log.i(TAG, "Ride ended. Cumulative summary recalculated.")
    }

    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
        return r * c
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

    fun updateRingBuffer(minutes: Int) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(ringBufferMinutes = minutes))
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

    private fun parseIso8601Time(timeStr: String): Long {
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        )
        for (sdf in formats) {
            try {
                val date = sdf.parse(timeStr)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return timeStr.toLongOrNull() ?: 0L
    }

    private fun readGpxPoints(file: File): List<GpxPoint> {
        val points = mutableListOf<GpxPoint>()
        try {
            if (!file.exists()) return points
            val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isIgnoringComments = true
            }
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(file)
            val allElements = doc.getElementsByTagName("*")
            for (i in 0 until allElements.length) {
                val node = allElements.item(i)
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                    val element = node as org.w3c.dom.Element
                    val nodeName = element.nodeName.lowercase(Locale.US)
                    if (nodeName.endsWith("trkpt") || element.localName?.lowercase(Locale.US) == "trkpt") {
                        val lat = element.getAttribute("lat").toDoubleOrNull() ?: 0.0
                        val lon = element.getAttribute("lon").toDoubleOrNull() ?: 0.0
                        var speedKmh = 0f
                        var timeMs = 0L

                        val children = element.getElementsByTagName("*")
                        for (j in 0 until children.length) {
                            val childNode = children.item(j)
                            if (childNode.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                                val childElem = childNode as org.w3c.dom.Element
                                val childName = childElem.nodeName.lowercase(Locale.US)
                                when {
                                    childName.contains("speed_kmh") || (childName.contains("speed") && !childName.contains("fps")) -> {
                                        val v = childElem.textContent.toFloatOrNull()
                                        if (v != null && v >= 0f) speedKmh = v
                                    }
                                    childName.contains("time") -> {
                                        val timeStr = childElem.textContent
                                        timeMs = parseIso8601Time(timeStr)
                                    }
                                }
                            }
                        }

                        if (timeMs <= 0L) timeMs = file.lastModified()
                        points.add(GpxPoint(latitude = lat, longitude = lon, speedKmh = speedKmh, timestampMs = timeMs))
                    }
                }
            }

            if (points.isNotEmpty()) {
                var lastValidTime = points[0].timestampMs.takeIf { it > 0L } ?: file.lastModified()
                for (i in points.indices) {
                    if (points[i].timestampMs <= 0L || (i > 0 && points[i].timestampMs <= points[i - 1].timestampMs)) {
                        lastValidTime += 1000L
                        points[i] = points[i].copy(timestampMs = lastValidTime)
                    } else {
                        lastValidTime = points[i].timestampMs
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to parse GPX file: ${file.name}", e)
        }
        return points
    }

    fun updateResolution(resolution: VideoResolution) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(resolution = resolution))
    }

    private fun createAndShareZip(fileName: String, chooserTitle: String, includeVideos: Boolean, includeLogs: Boolean) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _isZipGenerating.value = true
            try {
                val zipFile = File(context.cacheDir, fileName)
                if (zipFile.exists()) zipFile.delete()

                java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
                    if (includeVideos) {
                        val recDir = jp.hisanet.charisafe.util.FileUtils.getRecordingsDir(context)
                        recDir.listFiles()?.forEach { file ->
                            if (file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".mov"))) {
                                java.io.FileInputStream(file).use { fis ->
                                    zos.putNextEntry(java.util.zip.ZipEntry("recordings/${file.name}"))
                                    fis.copyTo(zos, bufferSize = 16384)
                                    zos.closeEntry()
                                }
                            }
                        }
                    }

                    if (includeLogs) {
                        val logsDir = jp.hisanet.charisafe.util.FileUtils.getLogsDir(context)
                        logsDir.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                java.io.FileInputStream(file).use { fis ->
                                    zos.putNextEntry(java.util.zip.ZipEntry("logs/${file.name}"))
                                    fis.copyTo(zos, bufferSize = 16384)
                                    zos.closeEntry()
                                }
                            }
                        }
                        val gpxDir = jp.hisanet.charisafe.util.FileUtils.getGpxDir(context)
                        gpxDir.listFiles()?.forEach { file ->
                            if (file.isFile && file.name.endsWith(".gpx")) {
                                java.io.FileInputStream(file).use { fis ->
                                    zos.putNextEntry(java.util.zip.ZipEntry("gpx/${file.name}"))
                                    fis.copyTo(zos, bufferSize = 16384)
                                    zos.closeEntry()
                                }
                            }
                        }
                        val debugDir = context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug")
                        debugDir.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                java.io.FileInputStream(file).use { fis ->
                                    zos.putNextEntry(java.util.zip.ZipEntry("debug_csv/${file.name}"))
                                    fis.copyTo(zos, bufferSize = 16384)
                                    zos.closeEntry()
                                }
                            }
                        }
                    }
                    zos.flush()
                }

                withContext(Dispatchers.Main) {
                    _isZipGenerating.value = false
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(intent, chooserTitle).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create and share zip: $fileName", e)
                withContext(Dispatchers.Main) {
                    _isZipGenerating.value = false
                }
            }
        }
    }

    fun shareAllData() {
        createAndShareZip("charisafe_all_data.zip", "ライドデータをすべて共有(ZIP)", includeVideos = true, includeLogs = true)
    }

    fun shareLogsOnly() {
        createAndShareZip("charisafe_logs_only.zip", "ログデータをすべて共有(ZIP)", includeVideos = false, includeLogs = true)
    }

    fun shareVideosOnly() {
        createAndShareZip("charisafe_videos_only.zip", "録画動画をすべて共有(ZIP)", includeVideos = true, includeLogs = false)
    }
}
