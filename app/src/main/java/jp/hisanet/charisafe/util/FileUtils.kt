package jp.hisanet.charisafe.util

import android.os.Environment
import java.io.File

object FileUtils {

    /**
     * 公開ストレージの Movies/ChariSafe を動画保存先とする
     * PCのファイルマネージャーや Google Files から直接アクセス可能
     */
    fun getRecordingsDir(): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dir = File(moviesDir, "ChariSafe")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            android.util.Log.i("FileUtils", "Created public recordings dir: ${dir.absolutePath}, success=$created")
        }
        return dir
    }

    fun getRingBufferDir(): File {
        val dir = File(getRecordingsDir(), "ring_buffer")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 公開ストレージの Documents/ChariSafe/gpx をGPXログ保存先とする
     */
    fun getGpxDir(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docsDir, "ChariSafe/gpx")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 公開ストレージの Documents/ChariSafe/logs をログ保存先とする
     */
    fun getLogsDir(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docsDir, "ChariSafe/logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
