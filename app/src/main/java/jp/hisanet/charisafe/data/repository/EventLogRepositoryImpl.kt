package jp.hisanet.charisafe.data.repository

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.domain.model.EventLog
import jp.hisanet.charisafe.domain.repository.EventLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EventLogRepositoryImpl(private val context: Context) : EventLogRepository {

    companion object {
        private const val TAG = "EventLogRepositoryImpl"
    }

    private val _eventLogs = MutableStateFlow<List<EventLog>>(emptyList())
    override val eventLogs: StateFlow<List<EventLog>> = _eventLogs.asStateFlow()

    private val _lastEvent = MutableStateFlow<EventLog?>(null)
    override val lastEvent: StateFlow<EventLog?> = _lastEvent.asStateFlow()

    override fun addEvent(eventLog: EventLog) {
        val currentList = _eventLogs.value.toMutableList()
        currentList.add(0, eventLog) // 最新を先頭に
        _eventLogs.value = currentList
        _lastEvent.value = eventLog
        Log.i(TAG, "Added new EventLog: ${eventLog.eventType} at speed ${eventLog.speedKmh}km/h")
    }

    override fun clearEvents() {
        _eventLogs.value = emptyList()
        _lastEvent.value = null
        Log.i(TAG, "Cleared all event logs")
    }
}
