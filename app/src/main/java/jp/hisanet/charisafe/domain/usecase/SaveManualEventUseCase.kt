package jp.hisanet.charisafe.domain.usecase

import jp.hisanet.charisafe.domain.repository.ConfigRepository
import jp.hisanet.charisafe.domain.repository.RecordingRepository
import java.io.File

class SaveManualEventUseCase(
    private val recordingRepository: RecordingRepository,
    private val configRepository: ConfigRepository
) {
    suspend fun execute(): File? {
        val config = configRepository.getConfig()
        val beforeMinutes = config.ringBufferMinutes
        return recordingRepository.saveManualEvent(beforeMinutes)
    }
}
