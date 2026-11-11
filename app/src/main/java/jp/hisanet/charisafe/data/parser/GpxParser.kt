package jp.hisanet.charisafe.data.parser

import android.util.Log
import jp.hisanet.charisafe.domain.model.GpxPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class GpxParser {
    companion object {
        private const val TAG = "GpxParser"
    }

    fun readGpxPoints(file: File): List<GpxPoint> {
        val points = mutableListOf<GpxPoint>()
        if (!file.exists()) return points
        try {
            val dbFactory = DocumentBuilderFactory.newInstance()
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc = dBuilder.parse(file)
            doc.documentElement.normalize()

            val nodeList = doc.getElementsByTagName("*")
            for (i in 0 until nodeList.length) {
                val node = nodeList.item(i)
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                    val element = node as org.w3c.dom.Element
                    if (element.nodeName.endsWith("trkpt") || element.localName?.lowercase(Locale.US) == "trkpt") {
                        val lat = element.getAttribute("lat").toDoubleOrNull() ?: 0.0
                        val lon = element.getAttribute("lon").toDoubleOrNull() ?: 0.0
                        var speedKmh = 0f
                        var timeMs = 0L

                        val children = element.getElementsByTagName("*")
                        for (j in 0 until children.length) {
                            val childNode = children.item(j)
                            if (childNode.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                                val childElem = childNode as org.w3c.dom.Element
                                val childName = childElem.nodeName.lowercase(Locale.US)
                                when {
                                    childName.contains("speed_kmh") || (childName.contains("speed") && !childName.contains("fps")) -> {
                                        val v = childElem.textContent.toFloatOrNull()
                                        if (v != null && v >= 0f) speedKmh = v
                                    }
                                    childName.contains("time") -> {
                                        val timeStr = childElem.textContent
                                        timeMs = parseIso8601Time(timeStr)
                                    }
                                }
                            }
                        }

                        if (timeMs <= 0L) timeMs = file.lastModified()
                        points.add(GpxPoint(latitude = lat, longitude = lon, speedKmh = speedKmh, timestampMs = timeMs))
                    }
                }
            }

            if (points.isNotEmpty()) {
                var lastValidTime = points[0].timestampMs.takeIf { it > 0L } ?: file.lastModified()
                for (i in points.indices) {
                    if (points[i].timestampMs <= 0L || (i > 0 && points[i].timestampMs <= points[i - 1].timestampMs)) {
                        lastValidTime += 1000L
                        points[i] = points[i].copy(timestampMs = lastValidTime)
                    } else {
                        lastValidTime = points[i].timestampMs
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GPX file: ${file.name}", e)
        }
        return points
    }

    private fun parseIso8601Time(timeStr: String): Long {
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            )
            for (format in formats) {
                try {
                    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val date = format.parse(timeStr)
                    if (date != null) return date.time
                } catch (_: Exception) {}
            }
            0L
        } catch (e: Exception) {
            0L
        }
    }
}
