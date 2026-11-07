package jp.hisanet.charisafe.data.session

import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.LocationPoint

sealed interface SessionEvent {
    val ts: Long // UTC Unix Timestamp (ms)
}

data class StartEvent(
    override val ts: Long = System.currentTimeMillis(),
    val sid: String,
    val ver: String = "1.0"
) : SessionEvent

data class StopEvent(
    override val ts: Long = System.currentTimeMillis(),
    val sid: String
) : SessionEvent

data class StateTickEvent(
    override val ts: Long = System.currentTimeMillis(),
    val st: String // "MOVING" / "STOPPED"
) : SessionEvent

data class GpsFixEvent(
    override val ts: Long = System.currentTimeMillis(),
    val point: LocationPoint,
    val spd: Float = 0f,
    val accuracy: Float = 0f
) : SessionEvent

data class GpsLostEvent(
    override val ts: Long = System.currentTimeMillis(),
    val reason: String
) : SessionEvent

data class GpsRestoredEvent(
    override val ts: Long = System.currentTimeMillis(),
    val point: LocationPoint,
    val spd: Float = 0f,
    val accuracy: Float = 0f
) : SessionEvent

data class IncidentEvent(
    override val ts: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val value: Float,
    val threshold: Float,
    val point: LocationPoint,
    val segmentId: String,
    val spd: Float = 0f
) : SessionEvent
