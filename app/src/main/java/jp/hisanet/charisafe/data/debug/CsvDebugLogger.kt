package jp.hisanet.charisafe.data.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvDebugLogger(
    private val context: Context,
    private val filePrefix: String,
    private val header: String
) {
    companion object {
        private const val TAG = "CsvDebugLogger"
    }

    private fun getDebugDir(): File {
        val dir = context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getDailyFile(): File {
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val fileName = "${filePrefix}_$dateStr.csv"
        val dir = getDebugDir()
        val file = File(dir, fileName)
        if (!file.exists()) {
            try {
                FileWriter(file, true).use { writer ->
                    writer.append("$header\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write CSV header for $fileName", e)
            }
        }
        return file
    }

    @Synchronized
    fun appendRow(rowCsv: String) {
        try {
            val file = getDailyFile()
            FileWriter(file, true).use { writer ->
                writer.append("$rowCsv\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append CSV row to $filePrefix", e)
        }
    }
}
