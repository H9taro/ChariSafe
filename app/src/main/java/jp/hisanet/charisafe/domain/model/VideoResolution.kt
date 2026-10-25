package jp.hisanet.charisafe.domain.model

enum class VideoResolution(
    val width: Int,
    val height: Int
) {
    SD_480P(640, 480),
    HD_720P(1280, 720),
    FHD_1080P(1920, 1080)
}
