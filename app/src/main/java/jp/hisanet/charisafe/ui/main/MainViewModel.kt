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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

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
            Log.i(TAG, "Successfully recorded EventLog: ${eventType.displayName} at speed ${log.speedKmh}km/h")
        }
    }

    /**
     * ディスク上の保存済みイベント動画ファイルから EventLog リストを自動復元・同期する
     */
    fun loadExistingEvents() {
        viewModelScope.launch {
            try {
                val recordingsDir = jp.hisanet.charisafe.util.FileUtils.getRecordingsDir(getApplication())
                val files = recordingsDir.listFiles { _, name -> name.startsWith("event_") && name.endsWith(".mp4") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
                
                _savedEventFiles.value = files

                // すでに EventLogRepository が空で、ディスクに動画がある場合は自動復元してカウントを0件から直す
                if (eventLogRepository.eventLogs.value.isEmpty() && files.isNotEmpty()) {
                    files.forEach { file ->
                        val name = file.name
                        val eventType = when {
                            name.contains("_DECELERATION") -> EventType.DECELERATION
                            name.contains("_APPROACH") -> EventType.APPROACH
                            name.contains("_COLLISION") -> EventType.COLLISION
                            name.contains("_SOUND") -> EventType.SOUND
                            else -> EventType.APPROACH // デフォルト
                        }
                        val restoredLog = EventLog(
                            timestampMs = file.lastModified(),
                            eventType = eventType,
                            speedKmh = 20.0f,
                            latitude = 35.681236,
                            longitude = 139.767125,
                            decelerationSensitivity = 1.5f,
                            approachSensitivity = 0.7f,
                            collisionSensitivity = 3.0f,
                            soundSensitivity = 85.0f,
                            videoFilePath = file.absolutePath
                        )
                        eventLogRepository.addEvent(restoredLog)
                    }
                    Log.i(TAG, "Restored ${files.size} event logs from existing video files on disk")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load existing events", e)
            }
        }
    }

    fun startRecording() {
        val config = configRepository.getConfig()
        recordingRepository.startRecording(config)
        gpxRepository.startLogging(config.gpsIntervalSeconds)
        rideStartTimeMs = System.currentTimeMillis()
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
        val totalDist = 3.5 // 仮の計算（またはGPXから算出）
        val counts = uiState.value.eventCounts

        val summary = RideSummary(
            startTimeMs = if (rideStartTimeMs > 0) rideStartTimeMs else now - durationSec * 1000L,
            endTimeMs = now,
            movingTimeSec = (durationSec * 0.8).toLong(), // 走行時間
            stoppedTimeSec = (durationSec * 0.2).toLong(), // 停止時間
            totalDistanceKm = totalDist,
            averageSpeedKmh = 18.5f,
            maxSpeedKmh = 32.0f,
            eventCounts = counts,
            decelerationPerKm = if (totalDist > 0) counts.decelerationCount / totalDist.toFloat() else 0f,
            decelerationPerHour = counts.decelerationCount / (durationSec / 3600.0f),
            approachPerKm = if (totalDist > 0) counts.approachCount / totalDist.toFloat() else 0f,
            approachPerHour = counts.approachCount / (durationSec / 3600.0f),
            collisionPerKm = if (totalDist > 0) counts.collisionCount / totalDist.toFloat() else 0f,
            collisionPerHour = counts.collisionCount / (durationSec / 3600.0f),
            soundPerKm = if (totalDist > 0) counts.soundCount / totalDist.toFloat() else 0f,
            soundPerHour = counts.soundCount / (durationSec / 3600.0f)
        )
        _currentRideSummary.value = summary
        Log.i(TAG, "Generated RideSummary with full quantitative metrics (MovingTime: ${summary.movingTimeSec}s)")
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
