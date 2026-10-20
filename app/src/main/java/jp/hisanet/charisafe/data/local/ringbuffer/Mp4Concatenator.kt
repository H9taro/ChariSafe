package jp.hisanet.charisafe.data.local.ringbuffer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object Mp4Concatenator {

    private const val TAG = "Mp4Concatenator"
    private const val BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    fun concatenate(inputFiles: List<File>, outputFile: File): Boolean {
        if (inputFiles.isEmpty()) {
            Log.e(TAG, "Input file list is empty")
            return false
        }

        if (inputFiles.size == 1) {
            return inputFiles.first().copyTo(outputFile, overwrite = true).exists()
        }

        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackIndexMap = HashMap<Int, Int>()
            val firstFile = inputFiles.first()
            val extractor = MediaExtractor()
            extractor.setDataSource(firstFile.absolutePath)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val newTrackIndex = muxer.addTrack(format)
                trackIndexMap[i] = newTrackIndex
            }
            extractor.release()

            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            var globalPtsOffsetUs = 0L

            for (file in inputFiles) {
                if (!file.exists() || file.length() == 0L) continue

                val fileExtractor = MediaExtractor()
                fileExtractor.setDataSource(file.absolutePath)

                val fileTrackMap = HashMap<Int, Int>()
                for (i in 0 until fileExtractor.trackCount) {
                    val firstKey = trackIndexMap.keys.firstOrNull() ?: 0
                    val mappedIndex = trackIndexMap[firstKey] ?: 0
                    fileTrackMap[i] = mappedIndex
                    fileExtractor.selectTrack(i)
                }

                var maxPtsInFile = 0L

                while (true) {
                    bufferInfo.size = fileExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break

                    val trackIndex = fileExtractor.sampleTrackIndex
                    val muxerTrackIndex = fileTrackMap[trackIndex] ?: 0

                    val samplePts = fileExtractor.sampleTime
                    bufferInfo.presentationTimeUs = globalPtsOffsetUs + samplePts
                    bufferInfo.flags = fileExtractor.sampleFlags

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                    if (samplePts > maxPtsInFile) {
                        maxPtsInFile = samplePts
                    }

                    fileExtractor.advance()
                }

                globalPtsOffsetUs += maxPtsInFile + 33_000L // 約33msのオフセット追加
                fileExtractor.release()
            }

            muxer.stop()
            muxer.release()

            Log.i(TAG, "Successfully concatenated ${inputFiles.size} segments into ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to concatenate MP4 segments", e)
            try {
                muxer?.release()
            } catch (ignored: Exception) {}
            return false
        }
    }
}
