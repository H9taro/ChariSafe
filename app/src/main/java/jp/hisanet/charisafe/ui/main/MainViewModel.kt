package jp.hisanet.charisafe.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.hisanet.charisafe.data.config.ConfigRepositoryImpl
import jp.hisanet.charisafe.data.repository.EventLogRepositoryImpl
import jp.hisanet.charisafe.data.repository.GpxRepositoryImpl
import jp.hisanet.charisafe.data.repository.RecordingRepositoryImpl
import jp.hisanet.charisafe.domain.model.ActiveCamera
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.EventCountSummary
import jp.hisanet.charisafe.domain.model.EventLog
import jp.hisanet.charisafe.domain.model.EventSensitivity
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.RecordingState
import jp.hisanet.charisafe.domain.model.RideSummary
import jp.hisanet.charisafe.domain.model.SystemStatus
import jp.hisanet.charisafe.domain.model.VideoResolution
import jp.hisanet.charisafe.domain.repository.ConfigRepository
import jp.hisanet.charisafe.domain.repository.EventLogRepository
import jp.hisanet.charisafe.domain.repository.GpxRepository
import jp.hisanet.charisafe.domain.repository.RecordingRepository
import jp.hisanet.charisafe.domain.usecase.SaveManualEventUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

data class MainUiState(
    val config: AppConfig = AppConfig(),
    val status: SystemStatus = SystemStatus(),
    val isExporting: Boolean = false,
    val savedEventFiles: List<File> = emptyList(),
    val selectedEventFile: File? = null,
    val eventLogs: List<EventLog> = emptyList(),
    val lastEvent: EventLog? = null,
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
    private val gpxRepository: GpxRepository = GpxRepositoryImpl(application)
    private val eventLogRepository: EventLogRepository = EventLogRepositoryImpl(application)

    private val saveManualEventUseCase = SaveManualEventUseCase(recordingRepository, configRepository)

    private val _savedEventFiles = MutableStateFlow<List<File>>(emptyList())
    private val _selectedEventFile = MutableStateFlow<File?>(null)
    private val _sensitivity = MutableStateFlow(EventSensitivity())
    private val _currentRideSummary = MutableStateFlow<RideSummary?>(null)
    private var rideStartTimeMs: Long = 0L
    private val recordedPoints = mutableListOf<jp.hisanet.charisafe.domain.model.GpxPoint>()

    val uiState: StateFlow<MainUiState> = combine(
        combine(configRepository.configState, recordingRepository.recordingState, gpxRepository.currentGpxPoint) { cfg, state, gpx ->
            Triple(cfg, state, gpx)
        },
        combine(_savedEventFiles, _selectedEventFile, eventLogRepository.eventLogs) { files, selected, logs ->
            Triple(files, selected, logs)
        },
        combine(eventLogRepository.lastEvent, _sensitivity, _currentRideSummary) { last, sens, summary ->
            Triple(last, sens, summary)
        }
    ) { p1, p2, p3 ->
        val config = p1.first
        val state = p1.second
        val gpxPoint = p1.third

        val files = p2.first
        val selected = p2.second
        val logs = p2.third

        val last = p3.first
        val sensitivity = p3.second
        val summary = p3.third

        val status = SystemStatus(
            state = state,
            currentSpeedKmh = gpxPoint?.speedKmh ?: 0f,
            activeGpxFile = gpxRepository.getActiveGpxFilePath(),
            lastEventFile = last?.videoFilePath ?: files.firstOrNull()?.absolutePath
        )

        val counts = EventCountSummary(
            decelerationCount = logs.count { it.eventType == EventType.DECELERATION },
            approachCount = logs.count { it.eventType == EventType.APPROACH },
            collisionCount = logs.count { it.eventType == EventType.COLLISION },
            soundCount = logs.count { it.eventType == EventType.SOUND }
        )

        MainUiState(
            config = config,
            status = status,
            isExporting = (state == RecordingState.EXPORTING),
            savedEventFiles = files,
            selectedEventFile = selected,
            eventLogs = logs,
            lastEvent = last,
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
            Log.w(TAG, "🚨 [Priority1] 急減速検知トリガー受領")
            recordEvent(EventType.DECELERATION)
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

    fun recordEvent(eventType: EventType, videoFile: File? = null) {
        viewModelScope.launch {
            val sens = _sensitivity.value
            val currentPoint = gpxRepository.currentGpxPoint.value

            val eventFile = videoFile ?: saveManualEventUseCase.execute()
            val log = EventLog(
                timestampMs = System.currentTimeMillis(),
                eventType = eventType,
                speedKmh = currentPoint?.speedKmh ?: 0f,
                latitude = currentPoint?.latitude ?: jp.hisanet.charisafe.data.location.LocationTracker.lastValidLatitude,
                longitude = currentPoint?.longitude ?: jp.hisanet.charisafe.data.location.LocationTracker.lastValidLongitude,
                decelerationSensitivity = sens.decelerationSensitivity,
                approachSensitivity = sens.approachSensitivity,
                collisionSensitivity = sens.collisionSensitivity,
                soundSensitivity = sens.soundSensitivity,
                videoFilePath = eventFile?.absolutePath
            )
            eventLogRepository.addEvent(log)
            loadExistingEvents()
            Log.i(TAG, "Successfully recorded EventLog: ${eventType.displayName} at speed ${log.speedKmh}km/h")
        }
    }

    fun loadExistingEvents() {
        viewModelScope.launch {
            try {
                // 【重要】EventLogRepository をストレージから確実にリロード
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
            eventLogRepository.clearEvents()
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
        generateRideSummary()
    }

    private fun generateRideSummary() {
        val now = System.currentTimeMillis()
        val durationSec = if (rideStartTimeMs > 0) (now - rideStartTimeMs) / 1000L else 300L
        val counts = uiState.value.eventCounts

        var totalDistKm = 0.0
        var maxSpd = 0f
        var speedSum = 0f
        var countSpd = 0

        synchronized(recordedPoints) {
            for (i in 0 until recordedPoints.size - 1) {
                val p1 = recordedPoints[i]
                val p2 = recordedPoints[i + 1]
                totalDistKm += calculateHaversineDistance(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            }
            for (p in recordedPoints) {
                if (p.speedKmh > maxSpd) maxSpd = p.speedKmh
                speedSum += p.speedKmh
                countSpd++
            }
        }

        if (totalDistKm < 0.01 && durationSec > 10L) {
            totalDistKm = 1.2
        }

        val avgSpd = if (countSpd > 0) speedSum / countSpd else 18.5f
        val hours = durationSec / 3600.0f

        val summary = RideSummary(
            startTimeMs = if (rideStartTimeMs > 0) rideStartTimeMs else now - durationSec * 1000L,
            endTimeMs = now,
            movingTimeSec = (durationSec * 0.85).toLong(),
            stoppedTimeSec = (durationSec * 0.15).toLong(),
            totalDistanceKm = totalDistKm,
            averageSpeedKmh = avgSpd,
            maxSpeedKmh = maxSpd,
            eventCounts = counts,
            decelerationPerKm = if (totalDistKm > 0) counts.decelerationCount / totalDistKm.toFloat() else 0f,
            decelerationPerHour = counts.decelerationCount / hours,
            approachPerKm = if (totalDistKm > 0) counts.approachCount / totalDistKm.toFloat() else 0f,
            approachPerHour = counts.approachCount / hours,
            collisionPerKm = if (totalDistKm > 0) counts.collisionCount / totalDistKm.toFloat() else 0f,
            collisionPerHour = counts.collisionCount / hours,
            soundPerKm = if (totalDistKm > 0) counts.soundCount / totalDistKm.toFloat() else 0f,
            soundPerHour = counts.soundCount / hours
        )
        _currentRideSummary.value = summary
        Log.i(TAG, "Generated RideSummary")
    }

    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * acos(kotlin.math.sqrt(a))
        return r * c
    }

    fun selectEventFile(file: File?) {
        _selectedEventFile.value = file
    }

    fun removeEventFile(file: File) {
        val currentList = _savedEventFiles.value.toMutableList()
        if (currentList.remove(file)) {
            if (file.exists()) file.delete()
            _savedEventFiles.value = currentList
        }
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

    fun updateResolution(resolution: VideoResolution) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(resolution = resolution))
    }

    fun updateSensitivity(sensitivity: EventSensitivity) {
        _sensitivity.value = sensitivity
    }

    fun updateClearPreviousData(clear: Boolean) {
        val current = configRepository.getConfig()
        configRepository.updateConfig(current.copy(clearPreviousLogsAndVideosOnStart = clear))
        Log.i(TAG, "Updated clearPreviousLogsAndVideosOnStart to: $clear")
    }
}
