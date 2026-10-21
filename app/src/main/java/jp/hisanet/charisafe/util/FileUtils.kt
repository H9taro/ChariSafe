package jp.hisanet.charisafe.util

import android.content.Context
import android.os.Environment
import java.io.File

object FileUtils {

    /**
     * 公開ストレージの Movies/ChariSafe を動画保存先とする
     * PCのファイルマネージャーや Google Files から直接アクセス可能
     */
    fun getRecordingsDir(context: Context): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dir = File(moviesDir, "ChariSafe")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            android.util.Log.i("FileUtils", "Created public recordings dir: ${dir.absolutePath}, success=$created")
        }
        return dir
    }

    fun getRingBufferDir(context: Context): File {
        val dir = File(getRecordingsDir(context), "ring_buffer")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 公開ストレージの Documents/ChariSafe/gpx をGPXログ保存先とする
     */
    fun getGpxDir(context: Context): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docsDir, "ChariSafe/gpx")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 公開ストレージの Documents/ChariSafe/logs をログ保存先とする
     */
    fun getLogsDir(context: Context): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docsDir, "ChariSafe/logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
