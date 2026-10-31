package jp.hisanet.charisafe.domain.repository

import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.RecordingState
import kotlinx.coroutines.flow.StateFlow

interface RecordingRepository {
    val recordingState: StateFlow<RecordingState>
    val approachScoreState: StateFlow<Float>
    val gForceState: StateFlow<Float>
    val soundAmplitudeState: StateFlow<Float>
    fun startRecording(config: AppConfig)
    fun stopRecording()
}
