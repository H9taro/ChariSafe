package jp.hisanet.charisafe.data.session

import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.LocationPoint
import jp.hisanet.charisafe.domain.model.RideMovementState
import java.io.File

interface RideSessionStore {
    /**
     * セッション開始
     */
    fun startSession(sessionId: String)

    /**
     * セッション終了
     */
    fun stopSession(reason: String = "NORMAL")

    /**
     * GPS測位記録
     */
    fun recordGpsFix(
        point: LocationPoint,
        speedKmh: Float,
        accuracyMeters: Float
    )

    /**
     * GPSロスト
     */
    fun recordGpsLost(reason: String = "LOST")

    /**
     * GPS復旧
     */
    fun recordGpsRestored(
        point: LocationPoint,
        speedKmh: Float,
        accuracyMeters: Float
    )

    /**
     * 移動状態記録
     */
    fun recordStateTick(
        state: RideMovementState
    )

    /**
     * インシデント記録
     */
    fun recordIncident(
        eventType: EventType,
        value: Float,
        threshold: Float,
        point: LocationPoint,
        segmentId: String
    )

    /**
     * 現在記録中のJSONL
     */
    fun getCurrentSessionFile(): File?

}
