package jp.hisanet.charisafe.data.export

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import jp.hisanet.charisafe.util.FileUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExportService(private val context: Context) {
    companion object {
        private const val TAG = "ZipExportService"
    }

    fun createAndShareZip(
        fileName: String,
        chooserTitle: String,
        includeVideos: Boolean,
        includeLogs: Boolean,
        onStateChanged: (Boolean) -> Unit
    ) {
        onStateChanged(true)
        try {
            val zipFile = File(context.cacheDir, fileName)
            if (zipFile.exists()) zipFile.delete()

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                if (includeVideos) {
                    val recDir = FileUtils.getRecordingsDir(context)
                    recDir.listFiles()?.forEach { file ->
                        if (file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".mov"))) {
                            FileInputStream(file).use { fis ->
                                zos.putNextEntry(ZipEntry("recordings/${file.name}"))
                                fis.copyTo(zos, bufferSize = 16384)
                                zos.closeEntry()
                            }
                        }
                    }
                }

                if (includeLogs) {
                    val logsDir = FileUtils.getLogsDir(context)
                    logsDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            FileInputStream(file).use { fis ->
                                zos.putNextEntry(ZipEntry("logs/${file.name}"))
                                fis.copyTo(zos, bufferSize = 16384)
                                zos.closeEntry()
                            }
                        }
                    }
                    val gpxDir = FileUtils.getGpxDir(context)
                    gpxDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name.endsWith(".gpx")) {
                            FileInputStream(file).use { fis ->
                                zos.putNextEntry(ZipEntry("gpx/${file.name}"))
                                fis.copyTo(zos, bufferSize = 16384)
                                zos.closeEntry()
                            }
                        }
                    }
                    val debugDir = context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug")
                    debugDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            FileInputStream(file).use { fis ->
                                zos.putNextEntry(ZipEntry("debug_csv/${file.name}"))
                                fis.copyTo(zos, bufferSize = 16384)
                                zos.closeEntry()
                            }
                        }
                    }
                }
                zos.flush()
            }

            onStateChanged(false)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create and share zip: $fileName", e)
            onStateChanged(false)
        }
    }

    fun shareAllData(onStateChanged: (Boolean) -> Unit) {
        createAndShareZip("charisafe_all_data.zip", "ライドデータをすべて共有(ZIP)", includeVideos = true, includeLogs = true, onStateChanged)
    }

    fun shareLogsOnly(onStateChanged: (Boolean) -> Unit) {
        createAndShareZip("charisafe_logs_only.zip", "ログデータをすべて共有(ZIP)", includeVideos = false, includeLogs = true, onStateChanged)
    }

    fun shareVideosOnly(onStateChanged: (Boolean) -> Unit) {
        createAndShareZip("charisafe_videos_only.zip", "録画動画をすべて共有(ZIP)", includeVideos = true, includeLogs = false, onStateChanged)
    }
}
