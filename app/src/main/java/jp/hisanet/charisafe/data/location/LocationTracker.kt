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

class LocationTracker(private val context: Context) {

    companion object {
        private const val TAG = "LocationTracker"
        // 初期値として東京駅ではなく、システムから取得可能な最新位置（取得できなければ海老名市等の仮リアルタイム座標）を保持
        var lastValidLatitude: Double = 35.4503f.toDouble() // 海老名市近辺のデフォルト
        var lastValidLongitude: Double = 139.3929f.toDouble()
        var lastLocationTimestampMs: Long = 0L
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var isTracking = false

    private val _currentLocation = MutableStateFlow<GpxPoint?>(null)
    val currentLocation: StateFlow<GpxPoint?> = _currentLocation.asStateFlow()

    private val locationListener by lazy {
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
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
                Log.i(TAG, "🟢 FRESH GPS/Network Location updated: lat=${point.latitude}, lon=${point.longitude}, speed=${point.speedKmh} km/h")
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

            // システムから即座に取得できる最も新しい既知の位置（GPS or Network）を初期キャッシュにセット
            val lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastKnownNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLastKnown = when {
                lastKnownGps != null && lastKnownNet != null -> if (lastKnownGps.time > lastKnownNet.time) lastKnownGps else lastKnownNet
                else -> lastKnownGps ?: lastKnownNet
            }

            if (bestLastKnown != null) {
                lastValidLatitude = bestLastKnown.latitude
                lastValidLongitude = bestLastKnown.longitude
                lastLocationTimestampMs = bestLastKnown.time
                Log.i(TAG, "Initialized lastValidLocation from system provider: lat=$lastValidLatitude, lon=$lastValidLongitude")
            }

            var providerRegistered = false
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceMeters,
                    locationListener
                )
                providerRegistered = true
                Log.i(TAG, "Started GPS_PROVIDER tracking")
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceMeters,
                    locationListener
                )
                providerRegistered = true
                Log.i(TAG, "Started NETWORK_PROVIDER tracking")
            }

            if (providerRegistered) {
                isTracking = true
            } else {
                Log.w(TAG, "No Location Provider is enabled on device")
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location tracking", e)
        }
    }
}
