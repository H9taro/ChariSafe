package jp.hisanet.charisafe.data.session

enum class SessionEventKind {

    START,
    STOP,

    STATE_TICK,

    GPS_FIX,
    GPS_LOST,
    GPS_RESTORED,

    APPROACH,
    DECELERATION,
    COLLISION,
    SOUND
}