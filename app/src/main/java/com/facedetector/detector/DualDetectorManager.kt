package com.facedetector.detector

import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.facedetector.camera.ImageSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class DisplayMode { DUAL, ACCURATE, FAST }

class DualDetectorManager(
    private val imageSaver: ImageSaver,
    private val onResultsReady: (List<DetectionResult>, Stats) -> Unit
) {
    companion object {
        private const val TAG = "DualDetectorManager"
    }

    data class Stats(
        val pipelineFps: Float,
        val accurateFps: Float,
        val fastFps: Float,
        val accurateCount: Int,
        val fastCount: Int,
        val consensusCount: Int,
        val displayMode: DisplayMode
    )

    private val accurateDetector = FaceDetectorWrapper(DetectorSource.ACCURATE)
    private val fastDetector = FaceDetectorWrapper(DetectorSource.FAST)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var frameCount = 0
    private var lastPipelineTime = System.currentTimeMillis()
    private var pipelineFps = 0f

    private val accurateFrameCount = AtomicInteger(0)
    private val fastFrameCount = AtomicInteger(0)
    private val lastAccurateTime = AtomicLong(System.currentTimeMillis())
    private val lastFastTime = AtomicLong(System.currentTimeMillis())
    private var accurateFps = 0f
    private var fastFps = 0f

    private var cachedAccurateResults: List<DetectionResult> = emptyList()

    var displayMode: DisplayMode = DisplayMode.DUAL

    fun processFrame(imageProxy: ImageProxy) {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastPipelineTime
        if (elapsed >= 1000) {
            pipelineFps = frameCount * 1000f / elapsed
            frameCount = 0
            lastPipelineTime = now
        }

        val runAccurate = (frameCount % 3 == 0) || cachedAccurateResults.isEmpty()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        scope.launch {
            try {
                val fastDeferred = async { fastDetector.detect(imageProxy) }
                val accurateDeferred = if (runAccurate) {
                    async { accurateDetector.detect(imageProxy) }
                } else null

                val fastResults = fastDeferred.await()
                updateFastFps()

                val accurateResults = if (runAccurate) {
                    val r = accurateDeferred!!.await()
                    cachedAccurateResults = r
                    updateAccurateFps()
                    r
                } else {
                    cachedAccurateResults
                }

                val merged = mergeResults(accurateResults, fastResults)
                val filtered = filterByDisplayMode(merged)

                if (merged.isNotEmpty()) {
                    try {
                        withContext(Dispatchers.IO) {
                            val imageBytes = imageProxyToJpegBytes(imageProxy)
                            imageSaver.saveFrame(imageBytes, rotationDegrees)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save detected face frame", e)
                    }
                }

                val stats = Stats(
                    pipelineFps = pipelineFps,
                    accurateFps = accurateFps,
                    fastFps = fastFps,
                    accurateCount = accurateResults.size,
                    fastCount = fastResults.size,
                    consensusCount = merged.count { it.source == DetectorSource.CONSENSUS },
                    displayMode = displayMode
                )

                withContext(Dispatchers.Main) {
                    onResultsReady(filtered, stats)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing failed", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun imageProxyToJpegBytes(imageProxy: ImageProxy): ByteArray {
        val nv21 = ByteArray(imageProxy.width * imageProxy.height * 3 / 2)
        var outputOffset = 0

        val yPlane = imageProxy.planes[0]
        val yBuffer = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yRow = ByteArray(yRowStride)

        for (row in 0 until imageProxy.height) {
            yBuffer.get(yRow, 0, minOf(yRowStride, yBuffer.remaining()))
            var col = 0
            while (col < imageProxy.width) {
                nv21[outputOffset++] = yRow[col * yPixelStride]
                col++
            }
        }

        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uRow = ByteArray(uRowStride)
        val vRow = ByteArray(vRowStride)

        for (row in 0 until imageProxy.height / 2) {
            uBuffer.get(uRow, 0, minOf(uRowStride, uBuffer.remaining()))
            vBuffer.get(vRow, 0, minOf(vRowStride, vBuffer.remaining()))

            var col = 0
            while (col < imageProxy.width / 2) {
                nv21[outputOffset++] = vRow[col * vPixelStride]
                nv21[outputOffset++] = uRow[col * uPixelStride]
                col++
            }
        }

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height),
            90,
            out
        )
        return out.toByteArray()
    }

    private fun updateFastFps() {
        val count = fastFrameCount.incrementAndGet()
        val now = System.currentTimeMillis()
        val elapsed = now - lastFastTime.get()
        if (elapsed >= 1000) {
            fastFps = count * 1000f / elapsed
            fastFrameCount.set(0)
            lastFastTime.set(now)
        }
    }

    private fun updateAccurateFps() {
        val count = accurateFrameCount.incrementAndGet()
        val now = System.currentTimeMillis()
        val elapsed = now - lastAccurateTime.get()
        if (elapsed >= 1000) {
            accurateFps = count * 1000f / elapsed
            accurateFrameCount.set(0)
            lastAccurateTime.set(now)
        }
    }

    private fun mergeResults(
        accurateList: List<DetectionResult>,
        fastList: List<DetectionResult>
    ): List<DetectionResult> {
        val result = mutableListOf<DetectionResult>()
        val fastMatched = BooleanArray(fastList.size)

        for (acc in accurateList) {
            var bestIou = 0f
            var bestIdx = -1
            for ((i, fast) in fastList.withIndex()) {
                val iou = computeIoU(acc.boundingBox, fast.boundingBox)
                if (iou > bestIou) {
                    bestIou = iou
                    bestIdx = i
                }
            }
            if (bestIou > 0.35f && bestIdx >= 0) {
                fastMatched[bestIdx] = true
                // Consensus: merge bounding boxes and landmarks from accurate
                result.add(acc.copy(source = DetectorSource.CONSENSUS))
            } else {
                result.add(acc)
            }
        }

        for ((i, fast) in fastList.withIndex()) {
            if (!fastMatched[i]) {
                result.add(fast)
            }
        }

        return result
    }

    private fun filterByDisplayMode(results: List<DetectionResult>): List<DetectionResult> {
        return when (displayMode) {
            DisplayMode.DUAL -> results
            DisplayMode.ACCURATE -> results.filter {
                it.source == DetectorSource.ACCURATE || it.source == DetectorSource.CONSENSUS
            }
            DisplayMode.FAST -> results.filter {
                it.source == DetectorSource.FAST || it.source == DetectorSource.CONSENSUS
            }
        }
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()

        return interArea / (aArea + bArea - interArea)
    }

    fun shutdown() {
        scope.cancel()
        accurateDetector.close()
        fastDetector.close()
    }
}
