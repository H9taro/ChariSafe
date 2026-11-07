package jp.hisanet.charisafe.domain.repository

import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.LocationPoint

interface RideSessionStore {
    fun startSession(sessionId: String)
    fun stopSession(sessionId: String)

    fun recordStateTick(state: String)
    fun recordGpsFix(point: LocationPoint, spd: Float, accuracy: Float)
    fun recordGpsLost(reason: String)
    fun recordGpsRestored(point: LocationPoint, spd: Float, accuracy: Float)

    fun recordIncident(
        eventType: EventType,
        value: Float,
        threshold: Float,
        point: LocationPoint,
        segmentId: String
    )

    fun getActiveLogFilePath(): String?
}
