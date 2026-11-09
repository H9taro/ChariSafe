package jp.hisanet.charisafe.data.session

import android.util.Log
import jp.hisanet.charisafe.domain.model.EventType
import jp.hisanet.charisafe.domain.model.LocationPoint
import jp.hisanet.charisafe.domain.model.RideMovementState
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class JsonlSessionParser {
    companion object {
        private const val TAG = "JsonlSessionParser"
    }

    fun parseSessionFile(file: File): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        if (!file.exists()) {
            try { Log.w(TAG, "Session file does not exist: ${file.absolutePath}") } catch (_: Throwable) {}
            return events
        }

        try {
            BufferedReader(FileReader(file)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        try {
                            val type = parseString(trimmed, "t")
                            val ts = parseLong(trimmed, "ts", System.currentTimeMillis())

                            val event: SessionEvent? = when (type) {
                                SessionEventKind.START.name -> {
                                    StartEvent(
                                        ts = ts,
                                        sid = parseString(trimmed, "sid"),
                                        ver = parseString(trimmed, "ver", "1.0")
                                    )
                                }
                                SessionEventKind.STOP.name -> {
                                    StopEvent(
                                        ts = ts,
                                        sid = parseString(trimmed, "sid")
                                    )
                                }
                                SessionEventKind.STATE_TICK.name -> {
                                    val stStr = parseString(trimmed, "st", "MOVING")
                                    val st = try {
                                        RideMovementState.valueOf(stStr)
                                    } catch (e: Exception) {
                                        RideMovementState.MOVING
                                    }
                                    StateTickEvent(ts = ts, st = st)
                                }
                                SessionEventKind.GPS_FIX.name -> {
                                    val lat = parseDouble(trimmed, "lat", 0.0)
                                    val lon = parseDouble(trimmed, "lon", 0.0)
                                    val ele = parseDouble(trimmed, "ele", 0.0)
                                    val spd = parseFloat(trimmed, "spd", 0f)
                                    val acc = parseFloat(trimmed, "acc", 0f)
                                    GpsFixEvent(
                                        ts = ts,
                                        point = LocationPoint(lat, lon, ele),
                                        spd = spd,
                                        accuracy = acc,
                                    )
                                }
                                SessionEventKind.GPS_LOST.name -> {
                                    GpsLostEvent(
                                        ts = ts,
                                        reason = parseString(trimmed, "reason", "UNKNOWN")
                                    )
                                }
                                SessionEventKind.GPS_RESTORED.name -> {
                                    val lat = parseDouble(trimmed, "lat", 0.0)
                                    val lon = parseDouble(trimmed, "lon", 0.0)
                                    val ele = parseDouble(trimmed, "ele", 0.0)
                                    val spd = parseFloat(trimmed, "spd", 0f)
                                    val acc = parseFloat(trimmed, "acc", 0f)
                                    GpsRestoredEvent(
                                        ts = ts,
                                        point = LocationPoint(lat, lon, ele),
                                        spd = spd,
                                        accuracy = acc
                                    )
                                }
                                else -> {
                                    val eventType = try {
                                        EventType.valueOf(type)
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (eventType != null) {
                                        val value = parseFloat(trimmed, "val", 0f)
                                        val threshold = parseFloat(trimmed, "th", 0f)
                                        val lat = parseDouble(trimmed, "lat", 0.0)
                                        val lon = parseDouble(trimmed, "lon", 0.0)
                                        val spd = parseFloat(trimmed, "spd", 0f)
                                        val segmentId = parseString(trimmed, "segId")
                                        IncidentEvent(
                                            ts = ts,
                                            eventType = eventType,
                                            value = value,
                                            threshold = threshold,
                                            point = LocationPoint(lat, lon, 0.0),
                                            segmentId = segmentId,
                                            spd = spd
                                        )
                                    } else {
                                        null
                                    }
                                }
                            }

                            if (event != null) {
                                events.add(event)
                            }
                        } catch (e: Exception) {
                            try { Log.w(TAG, "Skipping malformed or incomplete JSONL line: $trimmed", e) } catch (_: Throwable) {}
                        }
                    }
                    line = reader.readLine()
                }
            }
            try { Log.i(TAG, "Successfully parsed ${events.size} events from ${file.name}") } catch (_: Throwable) {}
        } catch (e: Exception) {
            try { Log.e(TAG, "Failed to parse session file: ${file.absolutePath}", e) } catch (_: Throwable) {}
        }

        return events
    }

    private fun parseString(json: String, key: String, default: String = ""): String {
        val strRegex = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        val matchStr = strRegex.find(json)
        if (matchStr != null) return matchStr.groupValues[1]

        val numRegex = "\"$key\"\\s*:\\s*([^,\\}\\]\\s]+)".toRegex()
        val matchNum = numRegex.find(json)
        return matchNum?.groupValues?.get(1)?.trim() ?: default
    }

    private fun parseLong(json: String, key: String, default: Long = 0L): Long {
        return parseString(json, key, "").toLongOrNull() ?: default
    }

    private fun parseDouble(json: String, key: String, default: Double = 0.0): Double {
        return parseString(json, key, "").toDoubleOrNull() ?: default
    }

    private fun parseFloat(json: String, key: String, default: Float = 0f): Float {
        return parseString(json, key, "").toFloatOrNull() ?: default
    }
}
