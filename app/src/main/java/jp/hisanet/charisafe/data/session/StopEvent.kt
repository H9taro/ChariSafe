package jp.hisanet.charisafe.data.session

data class StopEvent(
    val sid: String = "",
    override val ts: Long = System.currentTimeMillis(),
    val reason: String = "NORMAL"
) : SessionEvent {

    override val kind = SessionEventKind.STOP
}