package com.facedetector.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageSaver(private val context: Context) {

    companion object {
        private const val TAG = "ImageSaver"
    }

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val folderName = "FaceDetector"

    fun saveFrame(jpegBytes: ByteArray, rotationDegrees: Int) {
        val filename = "face_${dateFormat.format(Date())}.jpg"
        val bytesToSave = if (rotationDegrees % 360 == 0) {
            jpegBytes
        } else {
            rotateJpegBytes(jpegBytes, rotationDegrees)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(bytesToSave, filename)
        } else {
            saveViaFile(bytesToSave, filename)
        }
    }

    private fun rotateJpegBytes(jpegBytes: ByteArray, rotationDegrees: Int): ByteArray {
        val sourceBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return jpegBytes

        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = Bitmap.createBitmap(
            sourceBitmap,
            0,
            0,
            sourceBitmap.width,
            sourceBitmap.height,
            matrix,
            true
        )

        val output = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)

        if (rotatedBitmap !== sourceBitmap) {
            rotatedBitmap.recycle()
        }
        sourceBitmap.recycle()

        return output.toByteArray()
    }

    private fun saveViaMediaStore(jpegBytes: ByteArray, filename: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$folderName")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: run {
                Log.e(TAG, "MediaStore insert returned null for $filename")
                return
            }

        try {
            val outputStream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("MediaStore output stream was null")

            outputStream.use { stream ->
                stream.write(jpegBytes)
            }
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image via MediaStore", e)
            resolver.delete(uri, null, null)
        }
    }

    private fun saveViaFile(jpegBytes: ByteArray, filename: String) {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val detectorDir = File(picturesDir, folderName).also { it.mkdirs() }
        val file = File(detectorDir, filename)

        try {
            FileOutputStream(file).use { fos ->
                fos.write(jpegBytes)
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image via file", e)
        }
    }
}
