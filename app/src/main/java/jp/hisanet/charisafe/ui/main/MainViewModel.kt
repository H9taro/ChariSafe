package jp.hisanet.charisafe.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.hisanet.charisafe.data.config.ConfigRepositoryImpl
import jp.hisanet.charisafe.data.repository.EventLogRepositoryImpl
import jp.hisanet.charisafe.data.repository.GpxRepositoryImpl
import jp.hisanet.charisafe.data.repository.RecordingRepositoryImpl
import jp.hisanet.charisafe.data.debug.DecelerationDebugLogger
import jp.hisanet.charisafe.domain.model.ActiveCamera
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.EventCountSummary
import jp.hisanet.charisafe.domain.model.EventDetectionLog
import jp.hisanet.charisafe.domain.model.EventSensitivity
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.IncidentLog
import jp.hisanet.charisafe.domain.model.RecordingState
import jp.hisanet.charisafe.domain.model.RideSummary
import jp.hisanet.charisafe.domain.model.SystemStatus
import jp.hisanet.charisafe.domain.repository.ConfigRepository
import jp.hisanet.charisafe.domain.repository.EventLogRepository
import jp.hisanet.charisafe.domain.repository.GpxRepository
import jp.hisanet.charisafe.domain.repository.RecordingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.edit

data class MainUiState(
    val config: AppConfig = AppConfig(),
    val status: SystemStatus = SystemStatus(),
    val isExporting: Boolean = false,
    val savedEventFiles: List<File> = emptyList(),
    val selectedEventFile: File? = null,
    val eventDetectionLogs: List<EventDetectionLog> = emptyList(),
    val incidentLogs: List<IncidentLog> = emptyList(),
    val lastDetectionLog: EventDetectionLog? = null,
    val eventCounts: EventCountSummary = EventCountSummary(),
    val sensitivity: EventSensitivity = EventSensitivity(),
    val currentRideSummary: RideSummary? = null,
    val userMessage: String? = null
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

    private val prefs = application.getSharedPreferences("charisafe_app_config", android.content.Context.MODE_PRIVATE)
    private val _savedEventFiles = MutableStateFlow<List<File>>(emptyList())
    private val _selectedEventFile = MutableStateFlow<File?>(null)
    private val _sensitivity = MutableStateFlow(
        EventSensitivity(
            decelerationSensitivity = prefs.getFloat("key_decel_sensitivity", 1.5f),
            approachSensitivity = prefs.getFloat("key_approach_sensitivity", 0.7f),
            collisionSensitivity = prefs.getFloat("key_collision_sensitivity", 3.0f),
            soundSensitivity = prefs.getFloat("key_sound_sensitivity", 5000.0f)
        )
    )
    private val _currentRideSummary = MutableStateFlow<RideSummary?>(null)
    private var rideStartTimeMs: Long = 0L
    private val recordedPoints = mutableListOf<jp.hisanet.charisafe.domain.model.GpxPoint>()

    val uiState: StateFlow<MainUiState> = combine(
        combine(configRepository.configState, recordingRepository.recordingState, gpxRepository.currentGpxPoint) { cfg, state, gpx ->
            Triple(cfg, state, gpx)
        },
        combine(_savedEventFiles, _selectedEventFile, eventLogRepository.eventDetectionLogs) { files, selected, detections ->
            Triple(files, selected, detections)
        },
        combine(eventLogRepository.incidentLogs, eventLogRepository.lastDetectionLog, _sensitivity) { incidents, lastDet, sens ->
            Triple(incidents, lastDet, sens)
        },
        _currentRideSummary
    ) { p1, p2, p3, summary ->
        val config = p1.first
        val state = p1.second
        val gpxPoint = p1.third

        val files = p2.first
        val selected = p2.second
        val detections = p2.third

        val incidents = p3.first
        val lastDet = p3.second
        val sensitivity = p3.third

        val status = SystemStatus(
            state = state,
            currentSpeedKmh = gpxPoint?.speedKmh ?: 0f,
            activeGpxFile = gpxRepository.getActiveGpxFilePath(),
            lastEventFile = incidents.firstOrNull()?.videoFilePath ?: files.firstOrNull()?.absolutePath
        )

        val counts = EventCountSummary(
            decelerationCount = detections.count { it.eventType == EventType.DECELERATION },
            approachCount = detections.count { it.eventType == EventType.APPROACH },
            collisionCount = detections.count { it.eventType == EventType.COLLISION },
            soundCount = detections.count { it.eventType == EventType.SOUND }
        )

        MainUiState(
            config = config,
            status = status,
            isExporting = (state == RecordingState.EXPORTING),
            savedEventFiles = files,
            selectedEventFile = selected,
            eventDetectionLogs = detections,
            incidentLogs = incidents,
            lastDetectionLog = lastDet,
            eventCounts = counts,
            sensitivity = sensitivity,
            currentRideSummary = summary
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    init {
        loadExistingEvents()
        val gpxImpl = gpxRepository as? GpxRepositoryImpl
        gpxImpl?.onDecelerationDetected = {
            Log.w(TAG, "🚨 [Priority1] 減速検知トリガー受領")
            val sens = _sensitivity.value.decelerationSensitivity
            val currentPoint = gpxRepository.currentGpxPoint.value
            val lat = currentPoint?.latitude ?: jp.hisanet.charisafe.data.location.LocationTracker.lastValidLatitude
            val lon = currentPoint?.longitude ?: jp.hisanet.charisafe.data.location.LocationTracker.lastValidLongitude
            val speed = currentPoint?.speedKmh ?: 0f

            val detectionLog = EventDetectionLog(
                timestampMs = System.currentTimeMillis(),
                eventType = EventType.DECELERATION,
                speedKmh = speed,
                latitude = lat,
                longitude = lon,
                sensitivityValue = sens,
                sensitivityLabel = "減速: ${sens}秒",
                sensitivitySnapshot = _sensitivity.value
            )
            eventLogRepository.addDetectionLog(detectionLog)
            loadExistingEvents()
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

    fun startRecording() {
        val config = configRepository.getConfig()

        if (config.clearPreviousLogsAndVideosOnStart) {
            Log.w(TAG, "🧹 録画開始時データクリア有効: 過去のEventLogおよび保存動画を消去します")
            eventLogRepository.clearAll()
            try {
                val recordingsDir = jp.hisanet.charisafe.util.FileUtils.getRecordingsDir(getApplication())
                recordingsDir.listFiles { _, name -> name.startsWith("event_") && name.endsWith(".mp4") }
                    ?.forEach { it.delete() }
                loadExistingEvents()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear previous recordings on start", e)
            }
        }

        recordingRepository.startRecording(config)
        gpxRepository.startLogging(config.gpsIntervalSeconds)
        rideStartTimeMs = System.currentTimeMillis()
        synchronized(recordedPoints) { recordedPoints.clear() }
        _currentRideSummary.value = null
    }

    fun stopRecording() {
        recordingRepository.stopRecording()
        gpxRepository.stopLogging()

        val rideEndTimeMs = System.currentTimeMillis()
        val totalDurationSec = if (rideStartTimeMs > 0) (rideEndTimeMs - rideStartTimeMs) / 1000L else 0L

        val pointsCopy = synchronized(recordedPoints) { recordedPoints.toList() }
        var totalDistKm = 0.0
        var maxSpd = 0f
        var speedSum = 0f
        var speedCount = 0

        for (i in 1 until pointsCopy.size) {
            val p1 = pointsCopy[i - 1]
            val p2 = pointsCopy[i]
            totalDistKm += calculateHaversineDistance(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            if (p2.speedKmh > maxSpd) maxSpd = p2.speedKmh
            speedSum += p2.speedKmh
            speedCount++
        }

        val avgSpd = if (speedCount > 0) speedSum / speedCount else 0f
        val movingSec = if (avgSpd > 1.0f) (totalDistKm / avgSpd * 3600.0).toLong().coerceAtMost(totalDurationSec) else 0L
        val stoppedSec = (totalDurationSec - movingSec).coerceAtLeast(0L)

        val hours = if (totalDurationSec > 0) totalDurationSec / 3600.0 else 1.0
        val counts = uiState.value.eventCounts

        val summary = RideSummary(
            startTimeMs = rideStartTimeMs,
            endTimeMs = rideEndTimeMs,
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

        _currentRideSummary.value = summary
        loadExistingEvents()
        Log.i(TAG, "Ride ended. Distance: $totalDistKm km, Duration: $totalDurationSec sec")
    }

    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        // 浮動小数点誤差による a > 1.0 での acos(NaN) を防止するため coerceIn および asin を採用
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

    fun updateSensitivity(sensitivity: EventSensitivity) {
        _sensitivity.value = sensitivity
        prefs.edit {
            putFloat("key_decel_sensitivity", sensitivity.decelerationSensitivity)
                .putFloat("key_approach_sensitivity", sensitivity.approachSensitivity)
                .putFloat("key_collision_sensitivity", sensitivity.collisionSensitivity)
                .putFloat("key_sound_sensitivity", sensitivity.soundSensitivity)
        }
    }

    fun updateClearPreviousData(clear: Boolean) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(clearPreviousLogsAndVideosOnStart = clear))
        Log.i(TAG, "Updated clearPreviousLogsAndVideosOnStart to: $clear")
    }

    fun updateApproachCsvEnabled(enabled: Boolean) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(approachCsvEnabled = enabled))
        Log.i(TAG, "Updated approachCsvEnabled to: $enabled")
    }

    fun updateAudioCsvEnabled(enabled: Boolean) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(audioCsvEnabled = enabled))
        Log.i(TAG, "Updated audioCsvEnabled to: $enabled")
    }

    fun updateDecelerationCsvEnabled(enabled: Boolean) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(decelerationCsvEnabled = enabled))
        Log.i(TAG, "Updated decelerationCsvEnabled to: $enabled")
    }

    fun updateCollisionCsvEnabled(enabled: Boolean) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(collisionCsvEnabled = enabled))
        Log.i(TAG, "Updated collisionCsvEnabled to: $enabled")
    }

    fun shareDebugLogs() {
        jp.hisanet.charisafe.data.debug.DebugLogShareManager.shareDebugLogs(getApplication())
    }

    fun deleteDebugLogs() {
        jp.hisanet.charisafe.data.debug.DebugLogShareManager.deleteDebugLogs(getApplication())
    }
}
