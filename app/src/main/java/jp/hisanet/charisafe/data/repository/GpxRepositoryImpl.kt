package jp.hisanet.charisafe.data.repository

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.data.gpx.GpxFileWriter
import jp.hisanet.charisafe.data.location.LocationTracker
import jp.hisanet.charisafe.data.location.SpeedDecelerationDetector
import jp.hisanet.charisafe.domain.model.GpxPoint
import jp.hisanet.charisafe.domain.repository.GpxRepository
import jp.hisanet.charisafe.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GpxRepositoryImpl(private val context: Context) : GpxRepository {

    companion object {
        private const val TAG = "GpxRepositoryImpl"
    }

    private val locationTracker = LocationTracker(context)
    private var gpxWriter: GpxFileWriter? = null
    private var activeGpxFile: File? = null
    private var collectJob: Job? = null
    private val repositoryScope = CoroutineScope(Dispatchers.IO + Job())

    var onDecelerationDetected: (() -> Unit)? = null

    private val decelerationDetector = SpeedDecelerationDetector {
        Log.w(TAG, "🚨 急減速検知コールバック発火 -> 自動イベント保存を実行します")
        onDecelerationDetected?.invoke()
    }

    override val currentGpxPoint: StateFlow<GpxPoint?>
        get() = locationTracker.currentLocation

    override fun startLogging(intervalSeconds: Long) {
        val gpxDir = FileUtils.getGpxDir(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(gpxDir, "ride_${timestamp}.gpx")
        activeGpxFile = file
        gpxWriter = GpxFileWriter(file)

        locationTracker.startTracking(intervalSeconds)

        collectJob?.cancel()
        collectJob = repositoryScope.launch {
            locationTracker.currentLocation.collect { point ->
                if (point != null) {
                    gpxWriter?.appendPoint(point)
                    decelerationDetector.feedPoint(point)
                }
            }
        }
        Log.i(TAG, "Started GPX logging to: ${file.absolutePath}")
    }

    override fun stopLogging() {
        collectJob?.cancel()
        collectJob = null
        locationTracker.stopTracking()
        gpxWriter = null
        activeGpxFile = null
        Log.i(TAG, "Stopped GPX logging")
    }

    override fun getActiveGpxFilePath(): String? {
        return activeGpxFile?.absolutePath
    }
}
