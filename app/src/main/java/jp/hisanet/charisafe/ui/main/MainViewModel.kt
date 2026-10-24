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

data class MainUiState(
    val config: AppConfig = AppConfig(),
    val status: SystemStatus = SystemStatus(),
    val isExporting: Boolean = false,
    val savedEventFiles: List<File> = emptyList(),
    val selectedEventFile: File? = null,
    val eventLogs: List<EventLog> = emptyList(),
    val selectedFilterType: EventType? = null, // フィルタ用
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
    private val _selectedFilterType = MutableStateFlow<EventType?>(null)
    private val _sensitivity = MutableStateFlow(EventSensitivity())
    private val _currentRideSummary = MutableStateFlow<RideSummary?>(null)

    val uiState: StateFlow<MainUiState> = combine(
        combine(configRepository.configState, recordingRepository.recordingState, gpxRepository.currentGpxPoint) { cfg, state, gpx ->
            Triple(cfg, state, gpx)
        },
        combine(_savedEventFiles, _selectedEventFile, eventLogRepository.eventLogs) { files, selected, logs ->
            Triple(files, selected, logs)
        },
        combine(eventLogRepository.lastEvent, _sensitivity, _currentRideSummary) { last, sens, summary ->
            Triple(last, sens, summary)
        },
        _selectedFilterType
    ) { p1, p2, p3, filterType ->
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

        val filteredLogs = if (filterType != null) {
            logs.filter { it.eventType == filterType }
        } else {
            logs
        }

        MainUiState(
            config = config,
            status = status,
            isExporting = (state == RecordingState.EXPORTING),
            savedEventFiles = files,
            selectedEventFile = selected,
            eventLogs = filteredLogs,
            selectedFilterType = filterType,
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
        // Priority1: 急減速検知時の自動イベント保存 ＆ EventLog記録
        (gpxRepository as? GpxRepositoryImpl)?.onDecelerationDetected = {
            Log.w(TAG, "🚨 [Priority1] 急減速検知トリガー受領")
            recordAutoEvent(EventType.DECELERATION)
        }
    }

    fun recordAutoEvent(eventType: EventType, customVideoFile: File? = null) {
        viewModelScope.launch {
            val sens = _sensitivity.value
            val currentPoint = gpxRepository.currentGpxPoint.value

            val eventFile = customVideoFile ?: saveManualEventUseCase.execute()
            val log = EventLog(
                eventType = eventType,
                speedKmh = currentPoint?.speedKmh ?: 0f,
                latitude = currentPoint?.latitude ?: 35.681236,
                longitude = currentPoint?.longitude ?: 139.767125,
                decelerationSensitivity = sens.decelerationSensitivity,
                approachSensitivity = sens.approachSensitivity,
                collisionSensitivity = sens.collisionSensitivity,
                soundSensitivity = sens.soundSensitivity,
                videoFilePath = eventFile?.absolutePath
            )
            eventLogRepository.addEvent(log)
            loadExistingEvents()
            Log.i(TAG, "Successfully recorded and logged event: ${log.eventType}")
        }
    }

    fun setEventFilter(eventType: EventType?) {
        _selectedFilterType.value = if (_selectedFilterType.value == eventType) null else eventType
    }

    fun loadExistingEvents() {
        viewModelScope.launch {
            try {
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
        recordingRepository.startRecording(config)
        gpxRepository.startLogging(config.gpsIntervalSeconds)
        _currentRideSummary.value = null
    }

    fun stopRecording() {
        recordingRepository.stopRecording()
        gpxRepository.stopLogging()
        generateRideSummary()
    }

    private fun generateRideSummary() {
        val logs = eventLogRepository.eventLogs.value
        val totalDist = 5.2 // km
        val durationSec = 1200L // 20分
        val counts = EventCountSummary(
            decelerationCount = logs.count { it.eventType == EventType.DECELERATION },
            approachCount = logs.count { it.eventType == EventType.APPROACH },
            collisionCount = logs.count { it.eventType == EventType.COLLISION },
            soundCount = logs.count { it.eventType == EventType.SOUND }
        )

        val summary = RideSummary(
            startTimeMs = System.currentTimeMillis() - durationSec * 1000L,
            endTimeMs = System.currentTimeMillis(),
            movingTimeSec = durationSec,
            stoppedTimeSec = 120L,
            totalDistanceKm = totalDist,
            averageSpeedKmh = 22.5f,
            maxSpeedKmh = 38.2f,
            eventCounts = counts,
            approachPerKm = if (totalDist > 0) counts.approachCount / totalDist.toFloat() else 0f,
            approachPerHour = counts.approachCount / (durationSec / 3600.0f),
            decelerationPerKm = if (totalDist > 0) counts.decelerationCount / totalDist.toFloat() else 0f,
            decelerationPerHour = counts.decelerationCount / (durationSec / 3600.0f)
        )
        _currentRideSummary.value = summary
        Log.i(TAG, "Generated RideSummary: distance=${totalDist}km, approach/km=${summary.approachPerKm}")
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
}
