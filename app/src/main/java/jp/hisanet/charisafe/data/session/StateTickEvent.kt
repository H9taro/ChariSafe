package jp.hisanet.charisafe.data.session

import jp.hisanet.charisafe.domain.model.RideMovementState

data class StateTickEvent(
    override val ts: Long = System.currentTimeMillis(),
    val st: RideMovementState
) : SessionEvent {

    override val kind = SessionEventKind.STATE_TICK
}