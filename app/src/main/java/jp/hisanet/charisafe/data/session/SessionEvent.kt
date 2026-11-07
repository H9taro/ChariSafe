package jp.hisanet.charisafe.data.session

import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.LocationPoint
import jp.hisanet.charisafe.domain.model.RideMovementState

sealed interface SessionEvent {
    val ts: Long // UTC Unix Timestamp (ms)
    val kind: SessionEventKind
}

data class StartEvent(
    override val ts: Long = System.currentTimeMillis(),
    val sid: String,
    val ver: String = "1.0",
    override val kind: SessionEventKind = SessionEventKind.START
) : SessionEvent

data class StopEvent(
    override val ts: Long = System.currentTimeMillis(),
    val sid: String,
    override val kind: SessionEventKind = SessionEventKind.STOP
) : SessionEvent

data class StateTickEvent(
    override val ts: Long = System.currentTimeMillis(),
    val st: RideMovementState,
    override val kind: SessionEventKind = SessionEventKind.STATE_TICK
) : SessionEvent

data class GpsFixEvent(
    override val ts: Long = System.currentTimeMillis(),
    val point: LocationPoint,
    val spd: Float = 0f,
    val accuracy: Float = 0f,
    override val kind: SessionEventKind = SessionEventKind.GPS_FIX
) : SessionEvent

data class GpsLostEvent(
    override val ts: Long = System.currentTimeMillis(),
    val reason: String,
    override val kind: SessionEventKind = SessionEventKind.GPS_LOST
) : SessionEvent

data class GpsRestoredEvent(
    override val ts: Long = System.currentTimeMillis(),
    val point: LocationPoint,
    val spd: Float = 0f,
    val accuracy: Float = 0f,
    override val kind: SessionEventKind = SessionEventKind.GPS_RESTORED
) : SessionEvent

data class IncidentEvent(
    override val ts: Long = System.currentTimeMillis(),
    val eventType: EventType,
    val value: Float,
    val threshold: Float,
    val point: LocationPoint,
    val segmentId: String,
    val spd: Float = 0f,
    override val kind: SessionEventKind = when (eventType) {
        EventType.APPROACH -> SessionEventKind.APPROACH
        EventType.DECELERATION -> SessionEventKind.DECELERATION
        EventType.COLLISION -> SessionEventKind.COLLISION
        EventType.SOUND -> SessionEventKind.SOUND
    }
) : SessionEvent
