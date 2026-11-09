package jp.hisanet.charisafe.domain.repository

import android.content.Context
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.RecordingState
import kotlinx.coroutines.flow.StateFlow

interface RecordingRepository {
    val recordingState: StateFlow<RecordingState>
    val approachScoreState: StateFlow<Float>
    val gForceState: StateFlow<Float>
    val soundAmplitudeState: StateFlow<Float>
    fun startRecording(context: Context, config: AppConfig)
    fun stopRecording()
}
