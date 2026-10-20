package jp.hisanet.charisafe.domain.model

data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double = 0.0,
    val speedKmh: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
)
