package jp.hisanet.charisafe.data.config

import android.content.Context
import android.content.SharedPreferences
import jp.hisanet.charisafe.domain.model.AppConfig
import jp.hisanet.charisafe.domain.model.VideoResolution
import jp.hisanet.charisafe.domain.repository.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConfigRepositoryImpl(context: Context) : ConfigRepository {

    companion object {
        private const val PREF_NAME = "charisafe_app_config"
        private const val KEY_FPS = "key_fps"
        private const val KEY_GPS_INTERVAL = "key_gps_interval"
        private const val KEY_RING_BUFFER = "key_ring_buffer"
        private const val KEY_RESOLUTION = "key_resolution"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _configState = MutableStateFlow(loadFromPrefs())
    override val configState: StateFlow<AppConfig> = _configState.asStateFlow()

    private fun loadFromPrefs(): AppConfig {
        val fps = prefs.getInt(KEY_FPS, 10)
        val gpsInterval = prefs.getLong(KEY_GPS_INTERVAL, 30L)
        val ringBuffer = prefs.getInt(KEY_RING_BUFFER, 5)
        val resName = prefs.getString(KEY_RESOLUTION, VideoResolution.HD_720P.name) ?: VideoResolution.HD_720P.name
        val resolution = try {
            VideoResolution.valueOf(resName)
        } catch (e: Exception) {
            VideoResolution.HD_720P
        }

        return AppConfig(
            fps = fps,
            gpsIntervalSeconds = gpsInterval,
            ringBufferMinutes = ringBuffer,
            resolution = resolution
        )
    }

    override fun getConfig(): AppConfig = _configState.value

    override fun updateConfig(config: AppConfig) {
        prefs.edit()
            .putInt(KEY_FPS, config.fps)
            .putLong(KEY_GPS_INTERVAL, config.gpsIntervalSeconds)
            .putInt(KEY_RING_BUFFER, config.ringBufferMinutes)
            .putString(KEY_RESOLUTION, config.resolution.name)
            .apply()

        _configState.value = config
    }
}
