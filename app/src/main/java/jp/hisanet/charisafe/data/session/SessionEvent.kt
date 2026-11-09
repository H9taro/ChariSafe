package jp.hisanet.charisafe.data.session

sealed interface SessionEvent {

    /**
     * UTC Unix Timestamp (ms)
     */
    val ts: Long

    /**
     * シリアライズ種別
     */
    val kind: SessionEventKind
}