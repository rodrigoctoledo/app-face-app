package com.facedetector.detector

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

class StorageManager(private val context: Context) {

    private val videosDir: File
        get() {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            return File(picturesDir, "FaceDetector/videos").also { it.mkdirs() }
        }

    fun getAvailableStoragePercent(): Float {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        return if (totalBytes > 0) (availableBytes * 100f) / totalBytes else 0f
    }

    fun isStorageCritical(): Boolean = getAvailableStoragePercent() < 30f

    fun deleteOldestVideo(): Boolean {
        val videos = videosDir.listFiles { file ->
            file.isFile && file.name.endsWith(".mp4")
        } ?: return false

        if (videos.isEmpty()) return false

        val oldest = videos.minByOrNull { it.lastModified() } ?: return false
        return oldest.delete()
    }

    fun freeUpStorage() {
        while (isStorageCritical()) {
            if (!deleteOldestVideo()) break
        }
    }

    fun getVideoFilePath(hourIndex: Int): String {
        val timestamp = System.currentTimeMillis()
        val filename = "video_${hourIndex}_${timestamp}.mp4"
        return File(videosDir, filename).absolutePath
    }
}
