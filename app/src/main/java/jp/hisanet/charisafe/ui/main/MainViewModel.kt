package jp.hisanet.charisafe.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.hisanet.charisafe.data.config.ConfigRepositoryImpl
import jp.hisanet.charisafe.data.repository.GpxRepositoryImpl
import jp.hisanet.charisafe.data.repository.RecordingRepositoryImpl
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.RecordingState
import jp.hisanet.charisafe.domain.model.SystemStatus
import jp.hisanet.charisafe.domain.repository.ConfigRepository
import jp.hisanet.charisafe.domain.repository.GpxRepository
import jp.hisanet.charisafe.domain.repository.RecordingRepository
import jp.hisanet.charisafe.domain.usecase.SaveManualEventUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val config: AppConfig = AppConfig(),
    val status: SystemStatus = SystemStatus(),
    val isExporting: Boolean = false,
    val userMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val configRepository: ConfigRepository = ConfigRepositoryImpl(application)
    private val recordingRepository: RecordingRepository = RecordingRepositoryImpl(application)
    private val gpxRepository: GpxRepository = GpxRepositoryImpl(application)

    private val saveManualEventUseCase = SaveManualEventUseCase(recordingRepository, configRepository)

    val uiState: StateFlow<MainUiState> = combine(
        configRepository.configState,
        recordingRepository.recordingState,
        gpxRepository.currentGpxPoint
    ) { config, state, gpxPoint ->
        val status = SystemStatus(
            state = state,
            currentSpeedKmh = gpxPoint?.speedKmh ?: 0f,
            activeGpxFile = gpxRepository.getActiveGpxFilePath()
        )
        MainUiState(
            config = config,
            status = status,
            isExporting = (state == RecordingState.EXPORTING)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

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
            saveManualEventUseCase.execute()
        }
    }

    fun updateConfig(newConfig: AppConfig) {
        configRepository.updateConfig(newConfig)
    }
}
