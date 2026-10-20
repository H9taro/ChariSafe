package jp.hisanet.charisafe.domain.repository

import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.RecordingState
import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface RecordingRepository {
    val recordingState: StateFlow<RecordingState>
    fun startRecording(config: AppConfig)
    fun stopRecording()
    suspend fun saveManualEvent(beforeMinutes: Int, afterMinutes: Int): File?
}
