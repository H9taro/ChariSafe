package jp.hisanet.charisafe.data.session

import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.LocationPoint

data class IncidentEvent(
    override val ts: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val value: Float,
    val threshold: Float,
    val point: LocationPoint,
    val segmentId: String,
    val spd: Float = 0f
) : SessionEvent {

    override val kind =
        SessionEventKind.valueOf(eventType.name)
}