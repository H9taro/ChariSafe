package jp.hisanet.charisafe.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import jp.hisanet.charisafe.domain.model.GpxPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationTracker(context: Context) {

    companion object {
        private const val TAG = "LocationTracker"
        var lastValidLatitude: Double = 35.4503f.toDouble()
        var lastValidLongitude: Double = 139.3929f.toDouble()
        var lastValidSpeedKmh: Float = 0f
        var lastLocationTimestampMs: Long = 0L
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var isTracking = false

    private val _currentLocation = MutableStateFlow<GpxPoint?>(null)
    val currentLocation: StateFlow<GpxPoint?> = _currentLocation.asStateFlow()

    private var previousLocation: Location? = null

    private val locationListener by lazy {
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                var speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f

                // フォールバック: hasSpeed() がない場合や0の場合、前回位置との差分から速度を計算
                if (speedKmh < 0.5f && previousLocation != null) {
                    val distMeters = previousLocation!!.distanceTo(location)
                    val timeDeltaSec = (location.time - previousLocation!!.time) / 1000f
                    if (timeDeltaSec > 0.1f) {
                        val calcMs = distMeters / timeDeltaSec
                        speedKmh = (calcMs * 3.6f).coerceAtLeast(0f)
                    }
                }

                previousLocation = location

                val point = GpxPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    elevation = if (location.hasAltitude()) location.altitude else 0.0,
                    speedKmh = speedKmh,
                    timestampMs = if (location.time > 0) location.time else System.currentTimeMillis()
                )
                _currentLocation.value = point
                lastValidLatitude = location.latitude
                lastValidLongitude = location.longitude
                lastValidSpeedKmh = speedKmh
                lastLocationTimestampMs = point.timestampMs
                Log.i(TAG, "🟢 FRESH GPS Location: lat=${point.latitude}, lon=${point.longitude}, speed=${point.speedKmh} km/h")
            }

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

            val lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastKnownNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLastKnown = when {
                lastKnownGps != null && lastKnownNet != null -> if (lastKnownGps.time > lastKnownNet.time) lastKnownGps else lastKnownNet
                else -> lastKnownGps ?: lastKnownNet
            }

            if (bestLastKnown != null) {
                lastValidLatitude = bestLastKnown.latitude
                lastValidLongitude = bestLastKnown.longitude
                if (bestLastKnown.hasSpeed()) {
                    lastValidSpeedKmh = bestLastKnown.speed * 3.6f
                }
                lastLocationTimestampMs = bestLastKnown.time
                previousLocation = bestLastKnown
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
            previousLocation = null
            isTracking = false
            Log.i(TAG, "Stopped location tracking")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location tracking", e)
        }
    }
}
