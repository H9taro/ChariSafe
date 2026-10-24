package jp.hisanet.charisafe.domain.repository

import jp.hisanet.charisafe.domain.model.EventLog
import kotlinx.coroutines.flow.StateFlow

interface EventLogRepository {
    val eventLogs: StateFlow<List<EventLog>>
    val lastEvent: StateFlow<EventLog?>
    fun addEvent(eventLog: EventLog)
    fun reload() // ディスクから最新ログを再読み込み
    fun clearEvents()
}
