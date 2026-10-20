package jp.hisanet.charisafe.domain.model

data class SystemStatus(
    val state: RecordingState = RecordingState.IDLE,
    val currentSpeedKmh: Float = 0f,
    val batteryLevel: Int = -1,
    val batteryTempCelsius: Float = 0f,
    val elapsedTimeSec: Long = 0L,
    val recordedBytes: Long = 0L,
    val activeGpxFile: String? = null,
    val lastEventFile: String? = null,
    val errorMessage: String? = null
)
