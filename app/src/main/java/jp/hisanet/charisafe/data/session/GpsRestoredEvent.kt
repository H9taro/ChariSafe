package jp.hisanet.charisafe.data.session

import jp.hisanet.charisafe.domain.model.LocationPoint

data class GpsRestoredEvent(
    override val ts: Long = System.currentTimeMillis(),
    val point: LocationPoint,
    val spd: Float,
    val accuracy: Float
) : SessionEvent {

    override val kind = SessionEventKind.GPS_RESTORED
}