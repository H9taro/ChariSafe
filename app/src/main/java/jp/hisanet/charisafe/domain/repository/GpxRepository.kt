package jp.hisanet.charisafe.domain.repository

import jp.hisanet.charisafe.domain.model.GpxPoint
import kotlinx.coroutines.flow.StateFlow

interface GpxRepository {
    val currentGpxPoint: StateFlow<GpxPoint?>
    fun startLogging(intervalSeconds: Long)
    fun stopLogging()
    fun getActiveGpxFilePath(): String?
}
