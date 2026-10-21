package jp.hisanet.charisafe.domain.model

data class AppConfig(
    val fps: Int = 10,
    val gpsIntervalSeconds: Long = 30L,
    val ringBufferMinutes: Int = 5,
    val resolution: VideoResolution = VideoResolution.HD_720P,
    val audioEnabled: Boolean = false // デフォルトは風切り音防止・マイク不要の音声なし録画
)
