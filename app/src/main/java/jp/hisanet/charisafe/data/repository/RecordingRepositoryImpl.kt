package jp.hisanet.charisafe.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import jp.hisanet.charisafe.data.local.ringbuffer.Mp4Concatenator
import jp.hisanet.charisafe.data.local.ringbuffer.RingBufferManager
import jp.hisanet.charisafe.data.service.RecordingService
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.RecordingState
import jp.hisanet.charisafe.domain.repository.RecordingRepository
import jp.hisanet.charisafe.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingRepositoryImpl(private val context: Context) : RecordingRepository {

    companion object {
        private const val TAG = "RecordingRepositoryImpl"
    }

    private var recordingService: RecordingService? = null
    private var isBound = false
    private var ringBufferManager: RingBufferManager? = null

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    override val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RecordingService.LocalBinder
            recordingService = binder?.getService()
            isBound = true
            Log.i(TAG, "Bound to RecordingService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
            Log.i(TAG, "Unbound from RecordingService")
        }
    }

    init {
        val intent = Intent(context, RecordingService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun startRecording(config: AppConfig) {
        ringBufferManager = RingBufferManager(context, config.ringBufferMinutes)

        val startIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
            putExtra(RecordingService.EXTRA_TARGET_FPS, config.fps)
            putExtra(RecordingService.EXTRA_RESOLUTION_WIDTH, config.resolution.width)
            putExtra(RecordingService.EXTRA_RESOLUTION_HEIGHT, config.resolution.height)
        }
        context.startForegroundService(startIntent)
        _recordingState.value = RecordingState.RECORDING
        Log.i(TAG, "Started recording with config: $config")
    }

    override fun stopRecording() {
        val stopIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        context.startService(stopIntent)
        ringBufferManager?.clearAll()
        ringBufferManager = null
        _recordingState.value = RecordingState.IDLE
        Log.i(TAG, "Stopped recording")
    }

    override suspend fun saveManualEvent(beforeMinutes: Int): File? = withContext(Dispatchers.IO) {
        _recordingState.value = RecordingState.EXPORTING
        val ringManager = ringBufferManager
        if (ringManager == null) {
            Log.e(TAG, "RingBufferManager is null during manual event export")
            _recordingState.value = RecordingState.RECORDING
            return@withContext null
        }

        try {
            // ディスクから直近のセグメントを直接取得 (起動直後でも今ある分すべてを結合対象にする)
            val segments = ringManager.getRecentSegmentsForExport(beforeMinutes)
            if (segments.isEmpty()) {
                Log.w(TAG, "No ring buffer segments available for export yet")
                _recordingState.value = RecordingState.RECORDING
                return@withContext null
            }

            val recordingsDir = FileUtils.getRecordingsDir(context)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val eventFile = File(recordingsDir, "event_${timestamp}.mp4")

            val success = Mp4Concatenator.concatenate(segments, eventFile)

            _recordingState.value = RecordingState.RECORDING

            if (success) {
                Log.i(TAG, "Successfully saved manual event video instantly: ${eventFile.absolutePath}")
                eventFile
            } else {
                Log.e(TAG, "Failed to save manual event video")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving manual event video", e)
            _recordingState.value = RecordingState.RECORDING
            null
        }
    }
}
