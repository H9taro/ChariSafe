package jp.hisanet.charisafe.data.local.ringbuffer

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RingBufferManager(private val context: Context, private val ringBufferMinutes: Int = 5) {

    companion object {
        private const val TAG = "RingBufferManager"
    }

    fun createNextSegmentFile(): File {
        val dir = FileUtils.getRingBufferDir(context)
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

            val maxFiles = ringBufferMinutes.coerceAtLeast(1) + 2
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

    fun getPreviousCompletedSegmentFile(currentSegmentPath: String): File? {
        val dir = FileUtils.getRingBufferDir(context)
        try {
            val currentFile = File(currentSegmentPath)
            val files = dir.listFiles { _, name -> name.startsWith("seg_") && name.endsWith(".mp4") }
                ?.filter { file ->
                    file.exists() && 
                    file.length() > 50 * 1024 && 
                    file.absolutePath != currentFile.absolutePath
                }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            return files.lastOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get previous completed segment file", e)
        }
        return null
    }

    fun clearAll() {
        val dir = FileUtils.getRingBufferDir(context)
        try {
            dir.listFiles { _, name -> name.startsWith("seg_") && name.endsWith(".mp4") }
                ?.forEach { it.delete() }
            Log.i(TAG, "Cleared all ring buffer segment files")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear ring buffer files", e)
        }
    }
}
