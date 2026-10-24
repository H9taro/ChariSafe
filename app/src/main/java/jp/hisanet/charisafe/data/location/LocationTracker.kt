package jp.hisanet.charisafe.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import jp.hisanet.charisafe.domain.model.GpxPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationTracker(context: Context) {

    companion object {
        private const val TAG = "LocationTracker"
        // 誤った古いキャッシュ（昨日の川崎市等）を排除するため、デフォルトは東京駅ではなく無効値または最新実測値のみ
        var lastValidLatitude: Double = 35.681236
        var lastValidLongitude: Double = 139.767125
        var lastLocationTimestampMs: Long = 0L
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var isTracking = false

    private val _currentLocation = MutableStateFlow<GpxPoint?>(null)
    val currentLocation: StateFlow<GpxPoint?> = _currentLocation.asStateFlow()

    private val locationListener by lazy {
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // 鮮度チェック: 精度が極端に悪いか古いデータはスキップ
                val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
                val point = GpxPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    elevation = if (location.hasAltitude()) location.altitude else 0.0,
                    speedKmh = speedKmh,
                    timestampMs = location.time
                )
                _currentLocation.value = point
                lastValidLatitude = location.latitude
                lastValidLongitude = location.longitude
                lastLocationTimestampMs = location.time
                Log.i(TAG, "🟢 FRESH GPS Location updated: lat=${point.latitude}, lon=${point.longitude}, speed=${point.speedKmh} km/h")
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking(intervalSeconds: Long) {
        if (isTracking) return

        try {
            val minTimeMs = intervalSeconds * 1000L
            val minDistanceMeters = 0f

            // 【重要】古すぎるシステムキャッシュ (getLastKnownLocation) の採用を廃止し、リアルタイムの onLocationChanged のみを信頼する
            Log.i(TAG, "Starting fresh GPS tracking (ignoring stale system lastKnownLocation caches)...")

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceMeters,
                    locationListener
                )
                isTracking = true
                Log.i(TAG, "Started GPS_PROVIDER tracking")
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceMeters,
                    locationListener
                )
                isTracking = true
                Log.i(TAG, "Started NETWORK_PROVIDER tracking")
            } else {
                Log.w(TAG, "No Location Provider is enabled")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Location permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location tracking", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            locationManager.removeUpdates(locationListener)
            isTracking = false
            Log.i(TAG, "Stopped location tracking")
        } catch (e:Exception) {
            Log.e(TAG, "Failed to stop location tracking", e)
        }
    }
}
