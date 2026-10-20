package jp.hisanet.charisafe.domain.repository

import jp.hisanet.charisafe.domain.model.AppConfig
import kotlinx.coroutines.flow.StateFlow

interface ConfigRepository {
    val configState: StateFlow<AppConfig>
    fun getConfig(): AppConfig
    fun updateConfig(config: AppConfig)
}
