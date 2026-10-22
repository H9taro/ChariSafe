package jp.hisanet.charisafe.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * アプリ専用外部ストレージ (Android/data/jp.hisanet.charisafe/files/recordings)
     * 追加パーミッションなしで 100% 確実にファイル読み書き・一覧スキャンが可能
     */
    fun getRecordingsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getRingBufferDir(context: Context): File {
        val dir = File(getRecordingsDir(context), "ring_buffer")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getGpxDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "gpx")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getLogsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun scanFile(context: Context, file: File) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null
            ) { path, uri ->
                Log.i(TAG, "Scanned file: $path -> $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan file", e)
        }
    }

    fun extractSnapshotFromVideo(videoFile: File, outputFile: File): Boolean {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)

            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            val targetTimeUs = (durationUs - 500_000L).coerceAtLeast(0L)

            val bitmap = retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime

            if (bitmap != null) {
                FileOutputStream(outputFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                Log.i(TAG, "Successfully extracted snapshot image: ${outputFile.absolutePath}")
                outputFile.exists() && outputFile.length() > 0
            } else {
                Log.e(TAG, "Failed to extract bitmap from video")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting snapshot from video", e)
            false
        } finally {
            try {
                retriever?.release()
            } catch (ignored: Exception) {}
        }
    }
}
