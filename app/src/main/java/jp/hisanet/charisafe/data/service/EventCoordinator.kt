package jp.hisanet.charisafe.data.service

import android.util.Log
import jp.hisanet.charisafe.domain.model.EventType
import java.util.concurrent.atomic.AtomicBoolean

class EventCoordinator {
    companion object {
        private const val TAG = "EventCoordinator"
    }

    private val isProtectingLock = AtomicBoolean(false)

    private val activeRelatedDetectionIds = mutableListOf<String>()
    private var activeHighestSeverityType: EventType? = null

    fun isLocked(): Boolean = isProtectingLock.get()

    @Synchronized
    fun startIncidentSession(triggerEventType: EventType, triggerLogId: String) {
        activeRelatedDetectionIds.clear()
        activeRelatedDetectionIds.add(triggerLogId)
        activeHighestSeverityType = triggerEventType
    }

    @Synchronized
    fun recordDetectionDuringProtection(eventType: EventType, logId: String) {
        if (isProtectingLock.get()) {
            if (!activeRelatedDetectionIds.contains(logId)) {
                activeRelatedDetectionIds.add(logId)
            }
            val currentHighest = activeHighestSeverityType ?: eventType
            activeHighestSeverityType = getHigherSeverity(currentHighest, eventType)
        }
    }

    @Synchronized
    fun getAndClearActiveIncidentData(triggerEventType: EventType): Pair<List<String>, EventType> {
        val ids = activeRelatedDetectionIds.toList()
        val severity = activeHighestSeverityType ?: triggerEventType
        activeRelatedDetectionIds.clear()
        activeHighestSeverityType = null
        return Pair(ids, severity)
    }

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

    fun executeWithProtection(block: () -> Unit) {
        if (!isProtectingLock.compareAndSet(false, true)) {
            Log.w(TAG, "🔒 [SegmentProtection] 既にセグメント保護処理が進行中のため要求を調停しました")
            return
        }

        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "💥 セグメント保護処理中に例外が発生しました", e)
        } finally {
            isProtectingLock.set(false)
            Log.i(TAG, "🔓 [SegmentProtection] 保護ロックを正常に解除しました")
        }
    }
}
