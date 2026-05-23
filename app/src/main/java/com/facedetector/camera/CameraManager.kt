package com.facedetector.camera

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.MediaStoreOutputOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrameReady: (ImageProxy) -> Unit,
    private val onRecordingChanged: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "CameraManager"
        private const val VIDEO_FOLDER = "FaceDetector"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val recorder = Recorder.Builder()
        .setQualitySelector(
            QualitySelector.fromOrderedList(
                listOf(Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
        )
        .build()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val videoDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Resolution fallback list: try 720p → 480p → 1080p
    private val resolutionCandidates = listOf(
        Size(1280, 720),   // 16:9
        Size(640, 480),    // 4:3
        Size(1920, 1080)   // 16:9
    )

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        // Add aspect ratio configuration
        val aspectRatio = AspectRatio.RATIO_16_9

        val preview = Preview.Builder()
            .setTargetAspectRatio(aspectRatio)
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(aspectRatio)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    onFrameReady(imageProxy)
                }
            }

        val capture = VideoCapture.withOutput(recorder).also {
            it.targetRotation = rotation
            videoCapture = it
        }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis,
                capture
            )
            Log.d(TAG, "Camera bound successfully with aspect ratio $aspectRatio")
        } catch (e: Exception) {
            Log.w(TAG, "Camera bind failed, trying fallback", e)
            tryFallbackResolution(provider, rotation)
        }
    }

    private fun tryFallbackResolution(
        provider: ProcessCameraProvider,
        rotation: Int
    ) {
        try {
            provider.unbindAll()
            
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        onFrameReady(imageProxy)
                    }
                }

            val capture = VideoCapture.withOutput(recorder).also {
                it.targetRotation = rotation
                videoCapture = it
            }

            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
                capture
            )
            Log.d(TAG, "Camera bound successfully with fallback configuration")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback camera bind also failed", e)
            bindWithoutVideoCapture(provider, rotation)
        }
    }

    private fun bindWithoutVideoCapture(
        provider: ProcessCameraProvider,
        rotation: Int
    ) {
        try {
            provider.unbindAll()
            
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        onFrameReady(imageProxy)
                    }
                }

            videoCapture = null
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            Log.d(TAG, "Camera bound without video capture")
        } catch (e: Exception) {
            Log.e(TAG, "All camera configurations failed", e)
        }
    }

    fun startRecording() {
        if (activeRecording != null) return

        val capture = videoCapture ?: run {
            Log.e(TAG, "Video capture is not ready")
            return
        }

        try {
            val filename = "video_${videoDateFormat.format(Date())}.mp4"
            val contentValues = ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$VIDEO_FOLDER"
                )
            }

            val outputOptions = MediaStoreOutputOptions.Builder(
                context.contentResolver,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(contentValues).build()

            activeRecording = capture.output
                .prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d(TAG, "Recording started")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Log.e(TAG, "Recording failed: ${event.error}")
                            } else {
                                Log.d(TAG, "Recording saved: ${event.outputResults.outputUri}")
                            }
                            activeRecording = null
                            onRecordingChanged(false)
                        }
                    }
                }
            onRecordingChanged(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            activeRecording = null
            onRecordingChanged(false)
        }
    }

    fun stopRecording() {
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
        }
        activeRecording = null
        onRecordingChanged(false)
    }

    val isRecording: Boolean
        get() = activeRecording != null

    val canRecord: Boolean
        get() = videoCapture != null

    fun shutdown() {
        stopRecording()
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
