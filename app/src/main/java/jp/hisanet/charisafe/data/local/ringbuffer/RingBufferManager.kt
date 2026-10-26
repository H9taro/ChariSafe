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

    /**
     * イベント発生時に、直近のセグメントファイル（現行セグメントおよび直前セグメント）を
     * パブリックな録画保存ディレクトリ（recordings/）へコピーして保護する。
     */
    fun protectSegmentsForEvent(currentSegmentPath: String): List<String> {
        val dir = FileUtils.getRingBufferDir(context)
        val recordingsDir = FileUtils.getRecordingsDir(context)
        val protectedPaths = mutableListOf<String>()

        try {
            val files = dir.listFiles { _, name -> name.startsWith("seg_") && name.endsWith(".mp4") }
                ?.filter { it.exists() && it.length() > 50 * 1024 }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            if (files.isEmpty()) {
                val currentFile = File(currentSegmentPath)
                if (currentFile.exists() && currentFile.length() > 0) {
                    val destFile = File(recordingsDir, currentFile.name)
                    currentFile.copyTo(destFile, overwrite = true)
                    protectedPaths.add(destFile.absolutePath)
                }
                return protectedPaths
            }

            // 直近の最大2セグメント（直前セグメント ＋ 現行セグメント）を対象とする
            val targetSegments = files.takeLast(2)
            val currentFile = File(currentSegmentPath)
            val finalSegments = if (currentFile.exists() && currentFile.length() > 0 && !targetSegments.contains(currentFile)) {
                targetSegments + currentFile
            } else {
                targetSegments
            }

            for (seg in finalSegments) {
                if (seg.exists() && seg.length() > 0) {
                    val destFile = File(recordingsDir, seg.name)
                    seg.copyTo(destFile, overwrite = true)
                    protectedPaths.add(destFile.absolutePath)
                    Log.i(TAG, "Protected ring buffer segment: ${destFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to protect segments for event", e)
        }

        return protectedPaths
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
