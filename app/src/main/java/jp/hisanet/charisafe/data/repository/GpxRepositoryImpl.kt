package jp.hisanet.charisafe.data.repository

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.data.debug.DecelerationDebugLogger
import jp.hisanet.charisafe.data.gpx.GpxFileWriter
import jp.hisanet.charisafe.data.location.LocationTracker
import jp.hisanet.charisafe.data.location.SpeedDecelerationDetector
import jp.hisanet.charisafe.data.session.JsonlRideSessionStore
import jp.hisanet.charisafe.domain.model.GpxPoint
import jp.hisanet.charisafe.domain.model.LocationPoint
import jp.hisanet.charisafe.domain.repository.GpxRepository
import jp.hisanet.charisafe.data.session.RideSessionStore
import jp.hisanet.charisafe.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GpxRepositoryImpl private constructor(
    context: Context,
    private val decelerationDebugLogger: DecelerationDebugLogger? = null
) : GpxRepository {
    private val appContext = context.applicationContext
    constructor(context: Context) : this(context.applicationContext, null)

    companion object {
        private const val TAG = "GpxRepositoryImpl"
        @Volatile
        private var instance: GpxRepositoryImpl? = null

        fun getInstance(context: Context, decelerationDebugLogger: DecelerationDebugLogger? = null): GpxRepositoryImpl {
            return instance ?: synchronized(this) {
                instance ?: GpxRepositoryImpl(context.applicationContext, decelerationDebugLogger).also { instance = it }
            }
        }
    }

    private val locationTracker = LocationTracker(appContext)
    private val jsonlSessionStore = JsonlRideSessionStore.getInstance(appContext)
    private var gpxWriter: GpxFileWriter? = null
    private var activeGpxFile: File? = null
    private var collectJob: Job? = null
    private val repositoryScope = CoroutineScope(Dispatchers.IO + Job())

    var onDecelerationDetected: (() -> Unit)? = null

    private val decelerationDetector = SpeedDecelerationDetector(
        decelerationDebugLogger = decelerationDebugLogger,
        getTargetDecelerationMs2 = {
            val prefs = appContext.getSharedPreferences("charisafe_app_config", Context.MODE_PRIVATE)
            prefs.getFloat("key_decel_sensitivity", 2.5f)
        }
    ) {
        Log.w(TAG, "🚨 急減速検知コールバック発火 -> 自動イベント保存を実行します")
        onDecelerationDetected?.invoke()
    }

    private val _decelerationState = MutableStateFlow(0f)
    override val decelerationState: StateFlow<Float> = _decelerationState.asStateFlow()

    override val currentGpxPoint: StateFlow<GpxPoint?>
        get() = locationTracker.currentLocation

    init {
        repositoryScope.launch {
            decelerationDetector.decelerationState.collect {
                _decelerationState.value = it
            }
        }
    }

    override fun startLogging(intervalSeconds: Long) {
        val gpxDir = FileUtils.getGpxDir(appContext)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(gpxDir, "ride_${timestamp}.gpx")
        activeGpxFile = file
        gpxWriter = GpxFileWriter(file)
        jsonlSessionStore.startSession("REC_$timestamp")

        locationTracker.startTracking(intervalSeconds)

        collectJob?.cancel()
        collectJob = repositoryScope.launch {
            locationTracker.currentLocation.collect { point ->
                if (point != null) {
                    gpxWriter?.appendPoint(point)
                    decelerationDetector.feedPoint(point)
                    val pointModel = LocationPoint(point.latitude, point.longitude, point.elevation)
                    jsonlSessionStore.recordGpsFix(pointModel, point.speedKmh, 0f)
                }
            }
        }
        Log.i(TAG, "Started GPX logging to: ${file.absolutePath}")
    }

    override fun stopLogging() {
        collectJob?.cancel()
        collectJob = null
        locationTracker.stopTracking()
        jsonlSessionStore.stopSession("REC_STOP")
        gpxWriter = null
        activeGpxFile = null
        Log.i(TAG, "Stopped logging")
    }

    override fun getActiveGpxFilePath(): String? {
        return activeGpxFile?.absolutePath
    }

    override fun clearAll() {
        try {
            activeGpxFile?.delete()
            activeGpxFile = null
            gpxWriter = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear GPX files", e)
        }
    }
}
