package jp.hisanet.charisafe.data.local.ringbuffer

import android.content.Context
import android.util.Log
import jp.hisanet.charisafe.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

class RingBufferManager(private val context: Context, private val ringBufferMinutes: Int = 5) {

    companion object {
        private const val TAG = "RingBufferManager"
    }

    private val segmentQueue = LinkedList<File>()
    private val lockedSegments = HashSet<File>()

    @Synchronized
    fun createNextSegmentFile(): File {
        val dir = FileUtils.getRingBufferDir(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val segmentFile = File(dir, "seg_${timestamp}.mp4")

        segmentQueue.addLast(segmentFile)
        purgeOldSegments()

        Log.d(TAG, "Created next segment file: ${segmentFile.name}, queue size: ${segmentQueue.size}")
        return segmentFile
    }

    @Synchronized
    private fun purgeOldSegments() {
        val maxSegments = ringBufferMinutes + 1
        while (segmentQueue.size > maxSegments) {
            val oldest = segmentQueue.removeFirst()
            if (!lockedSegments.contains(oldest) && oldest.exists()) {
                val deleted = oldest.delete()
                Log.d(TAG, "Purged old segment file: ${oldest.name}, deleted=$deleted")
            }
        }
    }

    @Synchronized
    fun getRecentSegmentsForExport(beforeMinutes: Int): List<File> {
        val targetCount = beforeMinutes.coerceAtMost(segmentQueue.size)
        val result = mutableListOf<File>()

        for (i in (segmentQueue.size - targetCount) until segmentQueue.size) {
            val file = segmentQueue[i]
            if (file.exists() && file.length() > 0) {
                result.add(file)
                lockedSegments.add(file)
            }
        }
        Log.i(TAG, "Locked ${result.size} segments for event export")
        return result
    }

    @Synchronized
    fun unlockSegments(files: List<File>) {
        lockedSegments.removeAll(files.toSet())
        purgeOldSegments()
        Log.d(TAG, "Unlocked ${files.size} segments")
    }

    @Synchronized
    fun clearAll() {
        for (file in segmentQueue) {
            if (!lockedSegments.contains(file) && file.exists()) {
                file.delete()
            }
        }
        segmentQueue.clear()
        lockedSegments.clear()
        Log.i(TAG, "Cleared ring buffer manager queue and files")
    }
}
