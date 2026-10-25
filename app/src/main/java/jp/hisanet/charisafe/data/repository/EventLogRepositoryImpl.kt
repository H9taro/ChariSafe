package jp.hisanet.charisafe.data.repository

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.data.local.EventDetectionLogStore
import jp.hisanet.charisafe.data.local.IncidentLogStore
import jp.hisanet.charisafe.domain.model.EventDetectionLog
import jp.hisanet.charisafe.domain.model.IncidentLog
import jp.hisanet.charisafe.domain.repository.EventLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EventLogRepositoryImpl(private val context: Context) : EventLogRepository {

    companion object {
        private const val TAG = "EventLogRepositoryImpl"
    }

    private val _eventDetectionLogs = MutableStateFlow<List<EventDetectionLog>>(emptyList())
    override val eventDetectionLogs: StateFlow<List<EventDetectionLog>> = _eventDetectionLogs.asStateFlow()

    private val _incidentLogs = MutableStateFlow<List<IncidentLog>>(emptyList())
    override val incidentLogs: StateFlow<List<IncidentLog>> = _incidentLogs.asStateFlow()

    private val _lastDetectionLog = MutableStateFlow<EventDetectionLog?>(null)
    override val lastDetectionLog: StateFlow<EventDetectionLog?> = _lastDetectionLog.asStateFlow()

    init {
        reload()
    }

    override fun reload() {
        val loadedDetections = EventDetectionLogStore.loadLogs(context)
        _eventDetectionLogs.value = loadedDetections
        if (loadedDetections.isNotEmpty()) {
            _lastDetectionLog.value = loadedDetections.first()
        }

        val loadedIncidents = IncidentLogStore.loadIncidents(context)
        _incidentLogs.value = loadedIncidents

        Log.i(TAG, "Reloaded repository: ${loadedDetections.size} detections, ${loadedIncidents.size} incidents")
    }

    override fun addDetectionLog(log: EventDetectionLog) {
        val currentList = _eventDetectionLogs.value.toMutableList()
        currentList.add(0, log)
        _eventDetectionLogs.value = currentList
        _lastDetectionLog.value = log

        EventDetectionLogStore.saveLogs(context, currentList)
        Log.i(TAG, "Added EventDetectionLog: ${log.eventType} at speed ${log.speedKmh}km/h")
    }

    override fun addOrUpdateIncidentLog(incident: IncidentLog) {
        val currentList = _incidentLogs.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == incident.id }
        if (index >= 0) {
            currentList[index] = incident
        } else {
            currentList.add(0, incident)
        }
        _incidentLogs.value = currentList.sortedByDescending { it.timestampMs }

        IncidentLogStore.saveIncidents(context, _incidentLogs.value)
        Log.i(TAG, "Added/Updated IncidentLog: ${incident.triggerEventType} status=${incident.status}")
    }

    override fun clearAll() {
        _eventDetectionLogs.value = emptyList()
        _incidentLogs.value = emptyList()
        _lastDetectionLog.value = null

        EventDetectionLogStore.saveLogs(context, emptyList())
        IncidentLogStore.saveIncidents(context, emptyList())
        Log.i(TAG, "Cleared all detection logs and incident logs")
    }
}
