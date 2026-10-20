package jp.hisanet.charisafe.data.gpx

import android.util.Log
import jp.hisanet.charisafe.domain.model.GpxPoint
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GpxFileWriter(private val gpxFile: File) {

    companion object {
        private const val TAG = "GpxFileWriter"
        private const val FOOTER_TAGS = "\n    </trkseg>\n  </trk>\n</gpx>\n"
    }

    private val iso8601Format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        if (!gpxFile.exists() || gpxFile.length() == 0L) {
            initGpxFile()
        }
    }

    private fun initGpxFile() {
        try {
            val header = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="ChariSafe Android" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>ChariSafe Ride</name>
    <trkseg>
$FOOTER_TAGS""".trimIndent()

            gpxFile.writeText(header)
            Log.i(TAG, "Initialized GPX file: ${gpxFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GPX file", e)
        }
    }

    fun appendPoint(point: GpxPoint) {
        try {
            RandomAccessFile(gpxFile, "rw").use { file ->
                val length = file.length()
                val footerLength = FOOTER_TAGS.toByteArray().size
                val writePosition = if (length > footerLength) length - footerLength else length

                file.seek(writePosition)

                val timeStr = iso8601Format.format(Date(point.timestampMs))
                val pointXml = String.format(
                    Locale.US,
                    "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n" +
                            "        <ele>%.1f</ele>\n" +
                            "        <time>%s</time>\n" +
                            "        <extensions>\n" +
                            "          <speed_kmh>%.1f</speed_kmh>\n" +
                            "        </extensions>\n" +
                            "      </trkpt>",
                    point.latitude, point.longitude, point.elevation, timeStr, point.speedKmh
                )

                file.writeBytes(pointXml)
                file.writeBytes(FOOTER_TAGS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append GPX point", e)
        }
    }
}
