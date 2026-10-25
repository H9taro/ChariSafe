package jp.hisanet.charisafe.domain.model

enum class ActiveCamera {
    REAR,  // 後方 (リアカメラ)
    FRONT  // 前方 (フロントカメラ)
}

data class AppConfig(
    val activeCamera: ActiveCamera = ActiveCamera.REAR,
    val fps: Int = 10,
    val gpsIntervalSeconds: Long = 30L,
    val ringBufferMinutes: Int = 5,
    val resolution: VideoResolution = VideoResolution.HD_720P,
    val audioEnabled: Boolean = true,
    val decelerationCooldownSec: Long = 30L,
    val approachCooldownSec: Long = 60L,
    val collisionCooldownSec: Long = 0L,
    val soundCooldownSec: Long = 30L,
    val clearPreviousLogsAndVideosOnStart: Boolean = false
)
