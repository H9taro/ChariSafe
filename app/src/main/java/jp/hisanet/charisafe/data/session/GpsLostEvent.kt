package jp.hisanet.charisafe.data.session

data class GpsLostEvent(
    override val ts: Long = System.currentTimeMillis(),
    val reason: String
) : SessionEvent {

    override val kind = SessionEventKind.GPS_LOST
}