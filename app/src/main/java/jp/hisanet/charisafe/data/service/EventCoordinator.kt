package jp.hisanet.charisafe.data.service

import android.util.Log
import jp.hisanet.charisafe.domain.model.EventType
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class EventCoordinator {
    companion object {
        private const val TAG = "EventCoordinator"
        private const val SAVE_TIMEOUT_MS = 15_000L // 最大15秒で強制解除
    }

    private val isSavingLocked = AtomicBoolean(false)

    private val activeRelatedDetectionIds = mutableListOf<String>()
    private var activeHighestSeverityType: EventType? = null

    fun isLocked(): Boolean = isSavingLocked.get()

    @Synchronized
    fun startIncidentSession(triggerEventType: EventType, triggerLogId: String) {
        activeRelatedDetectionIds.clear()
        activeRelatedDetectionIds.add(triggerLogId)
        activeHighestSeverityType = triggerEventType
    }

    @Synchronized
    fun recordDetectionDuringLock(eventType: EventType, logId: String) {
        if (isSavingLocked.get()) {
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

    suspend fun <T> executeWithLock(block: suspend () -> T): T? {
        if (!isSavingLocked.compareAndSet(false, true)) {
            Log.w(TAG, "🔒 [EventSavingLock] 既に動画保存が進行中のため要求を抑止しました")
            return null
        }

        return try {
            withTimeoutOrNull(SAVE_TIMEOUT_MS) {
                block()
            } ?: run {
                Log.e(TAG, "⚠️ [TimeoutWatchdog] 動画保存処理が15秒を超過したため強制キャンセルされました")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 動画保存処理中に例外が発生しました", e)
            null
        } finally {
            isSavingLocked.set(false)
            Log.i(TAG, "🔓 [EventSavingLock] ロックを正常に解除しました")
        }
    }
}
