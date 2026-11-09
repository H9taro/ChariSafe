package jp.hisanet.charisafe.domain.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double
) {
    fun distanceTo(other: LocationPoint): Double {
        val earthRadiusMeters = 6371000.0

        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)

        val a =
            sin(dLat / 2.0).pow(2.0) +
                    cos(Math.toRadians(lat)) *
                    cos(Math.toRadians(other.lat)) *
                    sin(dLon / 2.0).pow(2.0)

        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))

        return earthRadiusMeters * c
    }
}
