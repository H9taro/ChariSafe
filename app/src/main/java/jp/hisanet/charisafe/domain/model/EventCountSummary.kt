package jp.hisanet.charisafe.domain.model

data class EventCountSummary(
    val decelerationCount: Int = 0,
    val approachCount: Int = 0,
    val collisionCount: Int = 0,
    val soundCount: Int = 0
) {
    fun getCount(eventType: EventType): Int {
        return when (eventType) {
            EventType.APPROACH -> approachCount
            EventType.DECELERATION -> decelerationCount
            EventType.COLLISION -> collisionCount
            EventType.SOUND -> soundCount
        }
    }
}
