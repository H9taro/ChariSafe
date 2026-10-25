package jp.hisanet.charisafe.data.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import jp.hisanet.charisafe.domain.model.ActiveCamera
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.VideoResolution
import jp.hisanet.charisafe.domain.repository.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConfigRepositoryImpl(context: Context) : ConfigRepository {

    companion object {
        private const val PREF_NAME = "charisafe_app_config"
        private const val KEY_ACTIVE_CAMERA = "key_active_camera"
        private const val KEY_FPS = "key_fps"
        private const val KEY_GPS_INTERVAL = "key_gps_interval"
        private const val KEY_RING_BUFFER = "key_ring_buffer"
        private const val KEY_RESOLUTION = "key_resolution"
        private const val KEY_AUDIO_ENABLED = "key_audio_enabled"
        private const val KEY_DECELERATION_COOLDOWN = "key_decel_cooldown"
        private const val KEY_APPROACH_COOLDOWN = "key_approach_cooldown"
        private const val KEY_COLLISION_COOLDOWN = "key_collision_cooldown"
        private const val KEY_SOUND_COOLDOWN = "key_sound_cooldown"
        private const val KEY_CLEAR_PREV_DATA = "key_clear_prev_data"
        private const val KEY_APPROACH_CSV_ENABLED = "key_approach_csv_enabled"
        private const val KEY_AUDIO_CSV_ENABLED = "key_audio_csv_enabled"
        private const val KEY_DECELERATION_CSV_ENABLED = "key_deceleration_csv_enabled"
        private const val KEY_COLLISION_CSV_ENABLED = "key_collision_csv_enabled"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _configState = MutableStateFlow(loadFromPrefs())
    override val configState: StateFlow<AppConfig> = _configState.asStateFlow()

    private fun loadFromPrefs(): AppConfig {
        val activeCamName = prefs.getString(KEY_ACTIVE_CAMERA, ActiveCamera.REAR.name) ?: ActiveCamera.REAR.name
        val activeCamera = try {
            ActiveCamera.valueOf(activeCamName)
        } catch (e: Exception) {
            ActiveCamera.REAR
        }

        val fps = prefs.getInt(KEY_FPS, 10)
        val gpsInterval = prefs.getLong(KEY_GPS_INTERVAL, 30L)
        val ringBuffer = prefs.getInt(KEY_RING_BUFFER, 5)
        val audioEnabled = prefs.getBoolean(KEY_AUDIO_ENABLED, true)

        val decelerationCooldown = prefs.getLong(KEY_DECELERATION_COOLDOWN, 30L)
        val approachCooldown = prefs.getLong(KEY_APPROACH_COOLDOWN, 60L)
        val collisionCooldown = prefs.getLong(KEY_COLLISION_COOLDOWN, 0L)
        val soundCooldown = prefs.getLong(KEY_SOUND_COOLDOWN, 30L)

        val clearPrevData = prefs.getBoolean(KEY_CLEAR_PREV_DATA, false)
        val approachCsv = prefs.getBoolean(KEY_APPROACH_CSV_ENABLED, false)
        val audioCsv = prefs.getBoolean(KEY_AUDIO_CSV_ENABLED, false)
        val decelCsv = prefs.getBoolean(KEY_DECELERATION_CSV_ENABLED, false)
        val collisionCsv = prefs.getBoolean(KEY_COLLISION_CSV_ENABLED, false)

        val resName = prefs.getString(KEY_RESOLUTION, VideoResolution.HD_720P.name) ?: VideoResolution.HD_720P.name
        val resolution = try {
            VideoResolution.valueOf(resName)
        } catch (e: Exception) {
            VideoResolution.HD_720P
        }

        return AppConfig(
            activeCamera = activeCamera,
            fps = fps,
            gpsIntervalSeconds = gpsInterval,
            ringBufferMinutes = ringBuffer,
            resolution = resolution,
            audioEnabled = audioEnabled,
            decelerationCooldownSec = decelerationCooldown,
            approachCooldownSec = approachCooldown,
            collisionCooldownSec = collisionCooldown,
            soundCooldownSec = soundCooldown,
            clearPreviousLogsAndVideosOnStart = clearPrevData,
            approachCsvEnabled = approachCsv,
            audioCsvEnabled = audioCsv,
            decelerationCsvEnabled = decelCsv,
            collisionCsvEnabled = collisionCsv
        )
    }

    override fun getConfig(): AppConfig = _configState.value

    override fun updateConfig(config: AppConfig) {
        prefs.edit {
            putString(KEY_ACTIVE_CAMERA, config.activeCamera.name)
                .putInt(KEY_FPS, config.fps)
                .putLong(KEY_GPS_INTERVAL, config.gpsIntervalSeconds)
                .putInt(KEY_RING_BUFFER, config.ringBufferMinutes)
                .putString(KEY_RESOLUTION, config.resolution.name)
                .putBoolean(KEY_AUDIO_ENABLED, config.audioEnabled)
                .putLong(KEY_DECELERATION_COOLDOWN, config.decelerationCooldownSec)
                .putLong(KEY_APPROACH_COOLDOWN, config.approachCooldownSec)
                .putLong(KEY_COLLISION_COOLDOWN, config.collisionCooldownSec)
                .putLong(KEY_SOUND_COOLDOWN, config.soundCooldownSec)
                .putBoolean(KEY_CLEAR_PREV_DATA, config.clearPreviousLogsAndVideosOnStart)
                .putBoolean(KEY_APPROACH_CSV_ENABLED, config.approachCsvEnabled)
                .putBoolean(KEY_AUDIO_CSV_ENABLED, config.audioCsvEnabled)
                .putBoolean(KEY_DECELERATION_CSV_ENABLED, config.decelerationCsvEnabled)
                .putBoolean(KEY_COLLISION_CSV_ENABLED, config.collisionCsvEnabled)
        }

        _configState.value = config
    }
}
