package com.facedetector.detector

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class VideoRecorder(
    private val context: Context,
    private val storageManager: StorageManager,
    private val scope: CoroutineScope
) {
    private val videoSize = Size(854, 480) // 480p
    private val frameRate = 15
    private val bitrate = 2_500_000 // 2.5 Mbps
    private val videoDuration = 3600_000L // 1 hora em ms

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1

    private val frameQueue: BlockingQueue<FrameData> = LinkedBlockingQueue(30)
    private val isRecording = AtomicBoolean(false)
    private var recordStartTime = 0L
    private var currentHourIndex = 0

    data class FrameData(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long
    )

    fun start() {
        if (isRecording.getAndSet(true)) return

        recordStartTime = System.currentTimeMillis()
        currentHourIndex = 0

        setupMediaCodec()

        scope.launch(Dispatchers.Default) {
            encodeLoop()
        }
    }

    private fun setupMediaCodec() {
        try {
            val outputFile = File(storageManager.getVideoFilePath(currentHourIndex))
            outputFile.parentFile?.mkdirs()

            mediaCodec = MediaCodec.createEncoderByType("video/avc").apply {
                val format = MediaFormat.createVideoFormat("video/avc", videoSize.width, videoSize.height)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addFrame(data: ByteArray, width: Int, height: Int, timestamp: Long) {
        if (!isRecording.get()) return

        val elapsed = System.currentTimeMillis() - recordStartTime
        if (elapsed > videoDuration) {
            rotateFile()
        }

        frameQueue.offer(FrameData(data, width, height, timestamp))
    }

    private fun rotateFile() {
        stop()
        currentHourIndex++
        storageManager.freeUpStorage()
        setupMediaCodec()
    }

    private fun encodeLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording.get()) {
            try {
                val frame = frameQueue.poll() ?: continue

                val inputBuffers = mediaCodec?.inputBuffers ?: continue
                val inputIndex = mediaCodec?.dequeueInputBuffer(10_000) ?: continue

                if (inputIndex >= 0) {
                    val inputBuffer = inputBuffers[inputIndex]
                    inputBuffer.clear()
                    inputBuffer.put(frame.data)

                    val ptsUsec = System.nanoTime() / 1000
                    mediaCodec?.queueInputBuffer(inputIndex, 0, frame.data.size, ptsUsec, 0)
                }

                val outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: continue
                if (outputIndex >= 0) {
                    val outputBuffer = mediaCodec?.getOutputBuffer(outputIndex) ?: continue
                    outputBuffer?.let {
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = mediaMuxer?.addTrack(mediaCodec!!.outputFormat!!) ?: -1
                            if (videoTrackIndex >= 0) mediaMuxer?.start()
                        }

                        if (videoTrackIndex >= 0) {
                            mediaMuxer?.writeSampleData(videoTrackIndex, it, bufferInfo)
                        }
                    }

                    mediaCodec?.releaseOutputBuffer(outputIndex, false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        isRecording.set(false)

        try {
            mediaCodec?.signalEndOfInputStream()
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            mediaMuxer?.stop()
            mediaMuxer?.release()
            mediaMuxer = null

            videoTrackIndex = -1
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
