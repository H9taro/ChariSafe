package jp.hisanet.charisafe.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import jp.hisanet.charisafe.data.config.ConfigRepositoryImpl
import jp.hisanet.charisafe.data.service.RecordingService
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.RecordingState
import jp.hisanet.charisafe.domain.repository.ConfigRepository
import jp.hisanet.charisafe.domain.repository.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class RecordingRepositoryImpl(private val context: Context) : RecordingRepository {

    companion object {
        private const val TAG = "RecordingRepositoryImpl"
    }

    private val configRepository: ConfigRepository = ConfigRepositoryImpl(context)
    private var recordingService: RecordingService? = null
    private var isBound = false

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
        val startIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
            putExtra(RecordingService.EXTRA_TARGET_FPS, config.fps)
            putExtra(RecordingService.EXTRA_RESOLUTION_WIDTH, config.resolution.width)
            putExtra(RecordingService.EXTRA_RESOLUTION_HEIGHT, config.resolution.height)
            putExtra(RecordingService.EXTRA_AUDIO_ENABLED, config.audioEnabled)
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
        _recordingState.value = RecordingState.IDLE
        Log.i(TAG, "Stopped recording")
    }

    override suspend fun saveManualEvent(beforeMinutes: Int): File? = withContext(Dispatchers.IO) {
        _recordingState.value = RecordingState.EXPORTING
        val service = recordingService
        if (service == null) {
            Log.e(TAG, "RecordingService is not bound during manual event export")
            _recordingState.value = RecordingState.RECORDING
            return@withContext null
        }

        try {
            val currentConfig = configRepository.getConfig()
            val targetMinutes = currentConfig.ringBufferMinutes

            val eventFile = suspendCancellableCoroutine<File?> { continuation ->
                service.exportEventAsync("手動保存", targetMinutes) { file ->
                    continuation.resume(file)
                }
            }

            _recordingState.value = RecordingState.RECORDING

            if (eventFile != null) {
                Log.i(TAG, "Successfully exported manual event video (${targetMinutes}m limit): ${eventFile.absolutePath}")
                eventFile
            } else {
                Log.e(TAG, "Failed to export manual event video")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving manual event video", e)
            _recordingState.value = RecordingState.RECORDING
            null
        }
    }
}
