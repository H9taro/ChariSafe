package jp.hisanet.charisafe.data.debug

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugLogShareManager {
    private const val TAG = "DebugLogShareManager"

    fun shareDebugLogs(context: Context) {
        try {
            val debugDir = context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug")
            if (!debugDir.exists() || debugDir.listFiles().isNullOrEmpty()) {
                android.widget.Toast.makeText(context, "共有できるデバッグログがありません", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val zipFile = File(context.cacheDir, "charisafe_debug_logs.zip")
            if (zipFile.exists()) zipFile.delete()

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                debugDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        FileInputStream(file).use { fis ->
                            zos.putNextEntry(ZipEntry(file.name))
                            fis.copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "デバッグログを共有")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share debug logs", e)
            android.widget.Toast.makeText(context, "ログ共有エラー: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteDebugLogs(context: Context) {
        try {
            val debugDir = context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug")
            if (debugDir.exists()) {
                debugDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }
            android.widget.Toast.makeText(context, "デバッグログをすべて削除しました", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete debug logs", e)
            android.widget.Toast.makeText(context, "ログ削除エラー: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
