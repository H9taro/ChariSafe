package jp.hisanet.charisafe.domain.repository

import jp.hisanet.charisafe.domain.model.EventDetectionLog
import jp.hisanet.charisafe.domain.model.IncidentLog
import kotlinx.coroutines.flow.StateFlow

interface EventLogRepository {
    val eventDetectionLogs: StateFlow<List<EventDetectionLog>>
    val incidentLogs: StateFlow<List<IncidentLog>>
    val lastDetectionLog: StateFlow<EventDetectionLog?>

    fun addDetectionLog(log: EventDetectionLog)
    fun addOrUpdateIncidentLog(incident: IncidentLog)
    fun reload()
    fun clearAll()
}
