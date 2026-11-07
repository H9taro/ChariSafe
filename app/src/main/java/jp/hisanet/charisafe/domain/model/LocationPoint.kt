package jp.hisanet.charisafe.domain.model

data class LocationPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double = 0.0
) {
    fun distanceTo(other: LocationPoint): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(other.lat - this.lat)
        val dLon = Math.toRadians(other.lon - this.lon)
        val lat1 = Math.toRadians(this.lat)
        val lat2 = Math.toRadians(other.lat)

        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) *
                kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadiusKm * c // kilometers
    }
}
