package jp.hisanet.charisafe.data.debug

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UiStateDebugLogger(context: Context) {
    private val csvLogger = CsvDebugLogger(
        context = context,
        filePrefix = "ui_state_debug",
        header = "timestamp,currentApproachScore,currentDecelValue,currentGValue,currentSoundAmplitude,speedKmh"
    )

    private var lastLoggedTimeMs = 0L

    @Synchronized
    fun log(
        approachScore: Float,
        decelVal: Float,
        gVal: Float,
        soundVal: Float,
        speedKmh: Float
    ) {
        val now = System.currentTimeMillis()
        // 負荷軽減のため、最低 200ms ごとに1回記録
        if (now - lastLoggedTimeMs < 200L) return
        lastLoggedTimeMs = now

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(now))
        val row = "$timestamp,$approachScore,$decelVal,$gVal,$soundVal,$speedKmh"
        csvLogger.appendRow(row)
    }
}
