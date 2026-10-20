package jp.hisanet.charisafe.util

import android.content.Context
import java.io.File

object FileUtils {

    fun getRecordingsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "recordings")
        if (!dir.exists()) dir.mkdirs()
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
}
