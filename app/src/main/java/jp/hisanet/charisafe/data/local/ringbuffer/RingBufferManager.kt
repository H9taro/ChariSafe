package jp.hisanet.charisafe.data.local.ringbuffer

import android.util.Log
import jp.hisanet.charisafe.util.FileUtils
import java.io.File

class RingBufferManager(private val ringBufferMinutes: Int = 5) {

    companion object {
        private const val TAG = "RingBufferManager"
    }

    fun createNextSegmentFile(): File {
        val dir = FileUtils.getRingBufferDir()
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val segmentFile = File(dir, "seg_${timestamp}.mp4")

        purgeOldSegments(dir)
        Log.i(TAG, "Prepared next segment file: ${segmentFile.name}")
        return segmentFile
    }

    private fun purgeOldSegments(dir: File) {
        try {
            val files = dir.listFiles { _, name -> name.startsWith("seg_") && name.endsWith(".mp4") }
                ?.sortedBy { it.lastModified() }
                ?: return

            // 保持する最大セグメント数 (リングバッファ時間 + 余裕1分)
            val maxSegments = ringBufferMinutes + 2
            if (files.size > maxSegments) {
                val excess = files.size - maxSegments
                for (i in 0 until excess) {
                    val oldFile = files[i]
                    if (oldFile.exists()) {
                        val deleted = oldFile.delete()
                        Log.d(TAG, "Purged old segment file: ${oldFile.name}, deleted=$deleted")
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
                ?.filter { it.exists() && it.length() > 0 }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            // 指定された分数（または現在あるすべてのファイル）を取得
            val targetCount = beforeMinutes.coerceAtMost(files.size).coerceAtLeast(1)
            val result = files.takeLast(targetCount)

            Log.i(TAG, "Found ${result.size} segments in disk for event export (total in dir: ${files.size})")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recent segments from disk", e)
            return emptyList()
        }
    }

    fun clearAll() {
        val dir = FileUtils.getRingBufferDir()
        try {
            dir.listFiles { _, name -> name.startsWith("seg_") && name.endsWith(".mp4") }
                ?.forEach { it.delete() }
            Log.i(TAG, "Cleared all ring buffer segment files")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear ring buffer files", e)
        }
    }
}
