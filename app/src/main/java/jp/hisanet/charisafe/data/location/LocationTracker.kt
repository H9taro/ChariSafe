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
            Log.d(TAG, "Location updated: lat=${point.latitude}, lon=${point.longitude}, speed=${point.speedKmh} km/h")
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

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceMeters,
                    locationListener
                )
                isTracking = true
                Log.i(TAG, "Started GPS tracking with interval: ${intervalSeconds}s")
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceMeters,
                    locationListener
                )
                isTracking = true
                Log.i(TAG, "Started Network location tracking with interval: ${intervalSeconds}s")
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location tracking", e)
        }
    }
}
