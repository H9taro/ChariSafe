package jp.hisanet.charisafe.domain.model

enum class VideoResolution(
    val width: Int,
    val height: Int,
    val label: String
) {
    SD_480P(640, 480, "480p (SD)"),
    HD_720P(1280, 720, "720p (HD)"),
    FHD_1080P(1920, 1080, "1080p (FHD)")
}
