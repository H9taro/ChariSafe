package jp.hisanet.charisafe.data.service

import jp.hisanet.charisafe.domain.model.EventType
import java.util.concurrent.atomic.AtomicBoolean

class EventCoordinator {
    companion object {
        private const val TAG = "EventCoordinator"
    }

    private val isProtectingLock = AtomicBoolean(false)

    private val activeRelatedDetectionIds = mutableListOf<String>()
    private var activeHighestSeverityType: EventType? = null

    private fun getHigherSeverity(a: EventType, b: EventType): EventType {
        val rank = mapOf(
            EventType.COLLISION to 4,
            EventType.DECELERATION to 3,
            EventType.SOUND to 2,
            EventType.APPROACH to 1
        )
        val rankA = rank[a] ?: 0
        val rankB = rank[b] ?: 0
        return if (rankB > rankA) b else a
    }

}
