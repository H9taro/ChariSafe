package jp.hisanet.charisafe.data.session

data class StartEvent(
    val sid: String,
    override val ts: Long = System.currentTimeMillis(),
    val ver: String
) : SessionEvent {
    override val kind = SessionEventKind.START
}