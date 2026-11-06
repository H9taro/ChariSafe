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
import java.util.Locale

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
                val hasSpeed = location.hasSpeed()
                val rawSpeedMs = if (hasSpeed) location.speed else 0f
                val hwSpeedKmh = rawSpeedMs * 3.6f

                var distMeters = 0f
                var timeDeltaSec = 0f
                var calcMs = 0f

                if (previousLocation != null) {
                    distMeters = previousLocation!!.distanceTo(location)
                    timeDeltaSec = (location.time - previousLocation!!.time) / 1000f
                    if (timeDeltaSec > 0.1f) {
                        calcMs = distMeters / timeDeltaSec
                    }
                }

                var finalSpeedKmh = hwSpeedKmh

                // フォールバック & 二重判定 (Dual Validation):
                // 1. ハードウェア速度が無いか 0.5km/h 未満の場合、距離差分から計算
                if (hwSpeedKmh < 0.5f) {
                    if (distMeters >= 0.5f && timeDeltaSec > 0.1f) { // 距離デッドバンド: 0.5m未満はノイズとして無視
                        finalSpeedKmh = (calcMs * 3.6f).coerceAtLeast(0f)
                    } else {
                        // 距離が 0.5m 未満の場合は移動なし (0.0km/h) と判定
                        finalSpeedKmh = 0f
                    }
                }

                // 2. 二重判定: ハードウェアが hasSpeed() == true でかつ 0km/h 付近、かつ移動距離が 1.0m 未満なら 0km/h を最優先
                if (hasSpeed && hwSpeedKmh < 0.5f && distMeters < 1.0f) {
                    finalSpeedKmh = 0f
                }

                previousLocation = location

                val point = GpxPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    elevation = if (location.hasAltitude()) location.altitude else 0.0,
                    speedKmh = finalSpeedKmh,
                    timestampMs = if (location.time > 0) location.time else System.currentTimeMillis()
                )
                _currentLocation.value = point
                lastValidLatitude = location.latitude
                lastValidLongitude = location.longitude
                lastValidSpeedKmh = finalSpeedKmh
                lastLocationTimestampMs = point.timestampMs

                Log.d(
                    TAG,
                    "📊 [GPS Evaluation] hasSpeed: $hasSpeed, location.speed: $rawSpeedMs, distMeters: ${String.format(Locale.US, "%.2f", distMeters)}, timeDeltaSec: ${String.format(Locale.US, "%.2f", timeDeltaSec)}, calcMs: ${String.format(Locale.US, "%.2f", calcMs)}, finalSpeedKmh: ${String.format(Locale.US, "%.2f", finalSpeedKmh)}"
                )
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
            if (lastKnownGps != null) {
                lastValidLatitude = lastKnownGps.latitude
                lastValidLongitude = lastKnownGps.longitude
                if (lastKnownGps.hasSpeed()) {
                    lastValidSpeedKmh = lastKnownGps.speed * 3.6f
                }
                lastLocationTimestampMs = lastKnownGps.time
                previousLocation = lastKnownGps
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
