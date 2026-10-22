package jp.hisanet.charisafe.data.local.ringbuffer

import android.util.Log
import jp.hisanet.charisafe.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RingBufferManager(private val ringBufferMinutes: Int = 5) {

    companion object {
        private const val TAG = "RingBufferManager"
    }

    fun createNextSegmentFile(): File {
        val dir = FileUtils.getRingBufferDir()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val segmentFile = File(dir, "seg_${timestamp}.mp4")

        purgeOldSegments(dir)
        Log.i(TAG, "Prepared next segment file: ${segmentFile.name}, ringBufferMinutes: $ringBufferMinutes")
        return segmentFile
    }

    private fun purgeOldSegments(dir: File) {
        try {
            val files = dir.listFiles { _, name -> name.startsWith("seg_") && name.endsWith(".mp4") }
                ?.sortedBy { it.lastModified() }
                ?: return

            val maxFiles = ringBufferMinutes.coerceAtLeast(1) + 1
            if (files.size > maxFiles) {
                val excess = files.size - maxFiles
                for (i in 0 until excess) {
                    val oldFile = files[i]
                    if (oldFile.exists()) {
                        val deleted = oldFile.delete()
                        Log.i(TAG, "Purged old ring buffer segment: ${oldFile.name}, deleted=$deleted")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to purge old segments", e)
        }
    }

    fun getRecentSegmentsForExport(beforeMinutes: Int): List<File> {
        val dir = FileUtils.getRingBufferDir()
        try {
            val files = dir.listFiles { _, name -> name.startsWith("seg_") && name.endsWith(".mp4") }
                ?.filter { it.exists() && it.length() > 100 * 1024 }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            if (files.isEmpty()) return emptyList()

            val completedFiles = if (files.size > 1) {
                files.dropLast(1)
            } else {
                files
            }

            val targetCount = beforeMinutes.coerceAtMost(completedFiles.size).coerceAtLeast(1)
            val result = completedFiles.takeLast(targetCount)

            Log.i(TAG, "Selected ${result.size} segments for export (beforeMinutes=$beforeMinutes, total available: ${files.size})")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recent segments from disk", e)
            return emptyList()
        }
    }
}
