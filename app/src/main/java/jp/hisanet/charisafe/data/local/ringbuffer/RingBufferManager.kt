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

    /**
     * イベント発生時に、すでに書き込みとファイナライズ（クローズ）が完了している
     * 「過去の完了済みセグメントファイル（最新1〜2本）」のみを安全に抽出して保護する。
     * ※ 現在書き込み中のファイルは絶対に対象外（コピーしない）とする。
     */
    fun protectSegmentsForEvent(currentSegmentPath: String): List<String> {
        val dir = FileUtils.getRingBufferDir(context)
        val recordingsDir = FileUtils.getRecordingsDir(context)
        val protectedPaths = mutableListOf<String>()

        try {
            val currentFile = File(currentSegmentPath)

            // ディレクトリから .mp4 ファイルを取得し、現在書き込み中のファイルは絶対除外する
            val files = dir.listFiles { _, name -> name.startsWith("seg_") && name.endsWith(".mp4") }
                ?.filter { file ->
                    file.exists() && 
                    file.length() > 50 * 1024 && 
                    file.absolutePath != currentFile.absolutePath
                }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            // 完全に完了した直近の過去セグメント（最大2本）のみを抽出
            val completedSegments = files.takeLast(2)

            for (seg in completedSegments) {
                if (seg.exists() && seg.length() > 0) {
                    val destFile = File(recordingsDir, seg.name)
                    seg.copyTo(destFile, overwrite = true)
                    protectedPaths.add(destFile.absolutePath)
                    Log.i(TAG, "Protected completed ring buffer segment: ${destFile.absolutePath} (size: ${seg.length()})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to protect completed segments for event", e)
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
