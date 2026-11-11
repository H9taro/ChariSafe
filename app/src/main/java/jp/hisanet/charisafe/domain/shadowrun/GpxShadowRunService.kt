package jp.hisanet.charisafe.domain.shadowrun

import android.content.Context
import jp.hisanet.charisafe.data.parser.GpxParser
import jp.hisanet.charisafe.data.repository.JsonlSessionRepository
import jp.hisanet.charisafe.domain.ride.RideMetricsCalculator
import jp.hisanet.charisafe.util.FileUtils
import java.io.File

class GpxShadowRunService(private val context: Context) {
    private val jsonlRepo = JsonlSessionRepository(context)
    private val gpxParser = GpxParser()

    fun runShadowRun(jsonlFiles: List<File>) {
        val gpxDir = FileUtils.getGpxDir(context)
        val gpxFiles = if (gpxDir.exists()) {
            gpxDir.listFiles { _, name -> name.endsWith(".gpx") }?.sortedBy { it.lastModified() } ?: emptyList()
        } else emptyList()

        if (gpxFiles.isEmpty() || jsonlFiles.isEmpty()) return

        for (jsonlFile in jsonlFiles) {
            if (!jsonlRepo.isAlreadyCompared(jsonlFile.name)) {
                val jsonlName = jsonlFile.name
                val timestampPart = jsonlName.substringAfter("session_").substringBefore(".jsonl")
                val matchingGpx = gpxFiles.find { it.name == "ride_${timestampPart}.gpx" }
                if (matchingGpx != null) {
                    val points = gpxParser.readGpxPoints(matchingGpx)
                    if (points.isNotEmpty()) {
                        val detections = jp.hisanet.charisafe.data.local.EventDetectionLogStore.loadLogs(context)
                        val counts = jp.hisanet.charisafe.domain.model.EventCountSummary(
                            approachCount = detections.count { it.eventType == jp.hisanet.charisafe.domain.model.EventType.APPROACH },
                            decelerationCount = detections.count { it.eventType == jp.hisanet.charisafe.domain.model.EventType.DECELERATION },
                            collisionCount = detections.count { it.eventType == jp.hisanet.charisafe.domain.model.EventType.COLLISION },
                            soundCount = detections.count { it.eventType == jp.hisanet.charisafe.domain.model.EventType.SOUND }
                        )
                        val gpxSummary = RideMetricsCalculator.calculateSummaryFromPoints(points, counts, gpxFileName = matchingGpx.name)
                        jsonlRepo.compareWithGpxSummary(jsonlFile, gpxSummary)
                    }
                }
            }
        }
    }
}
