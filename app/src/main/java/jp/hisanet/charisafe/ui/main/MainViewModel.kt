package jp.hisanet.charisafe.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.hisanet.charisafe.data.config.ConfigRepositoryImpl
import jp.hisanet.charisafe.data.repository.GpxRepositoryImpl
import jp.hisanet.charisafe.data.repository.RecordingRepositoryImpl
import jp.hisanet.charisafe.domain.model.ActiveCamera
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.RecordingState
import jp.hisanet.charisafe.domain.model.SystemStatus
import jp.hisanet.charisafe.domain.model.VideoResolution
import jp.hisanet.charisafe.domain.repository.ConfigRepository
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

data class MainUiState(
    val config: AppConfig = AppConfig(),
    val status: SystemStatus = SystemStatus(),
    val isExporting: Boolean = false,
    val savedEventFiles: List<File> = emptyList(),
    val selectedEventFile: File? = null,
    val userMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val configRepository: ConfigRepository = ConfigRepositoryImpl(application)
    private val recordingRepository: RecordingRepository = RecordingRepositoryImpl(application)
    private val gpxRepository: GpxRepository = GpxRepositoryImpl(application)

    private val saveManualEventUseCase = SaveManualEventUseCase(recordingRepository, configRepository)

    private val _savedEventFiles = MutableStateFlow<List<File>>(emptyList())
    private val _selectedEventFile = MutableStateFlow<File?>(null)

    val uiState: StateFlow<MainUiState> = combine(
        configRepository.configState,
        recordingRepository.recordingState,
        gpxRepository.currentGpxPoint,
        _savedEventFiles,
        _selectedEventFile
    ) { config, state, gpxPoint, files, selected ->
        val status = SystemStatus(
            state = state,
            currentSpeedKmh = gpxPoint?.speedKmh ?: 0f,
            activeGpxFile = gpxRepository.getActiveGpxFilePath(),
            lastEventFile = files.firstOrNull()?.absolutePath
        )
        MainUiState(
            config = config,
            status = status,
            isExporting = (state == RecordingState.EXPORTING),
            savedEventFiles = files,
            selectedEventFile = selected
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    init {
        loadExistingEvents()
        (gpxRepository as? GpxRepositoryImpl)?.onDecelerationDetected = {
            Log.w(TAG, "🚨 [Priority1] 急減速検知トリガー受領: 自動イベント保存を開始します")
            triggerManualEvent()
        }
    }

    fun loadExistingEvents() {
        viewModelScope.launch {
            try {
                val recordingsDir = jp.hisanet.charisafe.util.FileUtils.getRecordingsDir()
                val files = recordingsDir.listFiles { _, name -> name.startsWith("event_") && name.endsWith(".mp4") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
                
                // リストに差分がある、または最新化が必要な場合に更新
                _savedEventFiles.value = files
                Log.i(TAG, "Loaded ${files.size} event videos from storage")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load existing events", e)
            }
        }
    }

    fun startRecording() {
        val config = configRepository.getConfig()
        recordingRepository.startRecording(config)
        gpxRepository.startLogging(config.gpsIntervalSeconds)
    }

    fun stopRecording() {
        recordingRepository.stopRecording()
        gpxRepository.stopLogging()
    }

    fun triggerManualEvent() {
        viewModelScope.launch {
            Log.i(TAG, "Triggering event export (manual or automatic)...")
            val eventFile = saveManualEventUseCase.execute()
            if (eventFile != null) {
                Log.i(TAG, "Event successfully saved to: ${eventFile.absolutePath}")
                loadExistingEvents() // 即座に最新リストを再スキャンして反映
                _selectedEventFile.value = eventFile
            } else {
                Log.e(TAG, "Event export returned null file")
            }
        }
    }

    fun selectEventFile(file: File?) {
        _selectedEventFile.value = file
    }

    fun removeEventFile(file: File) {
        val currentList = _savedEventFiles.value.toMutableList()
        if (currentList.remove(file)) {
            if (file.exists()) {
                file.delete()
            }
            _savedEventFiles.value = currentList
            if (_selectedEventFile.value == file) {
                _selectedEventFile.value = null
            }
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
}
