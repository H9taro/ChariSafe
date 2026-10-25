package jp.hisanet.charisafe.util

import android.content.Context
import java.io.File

object FileUtils {

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
}
