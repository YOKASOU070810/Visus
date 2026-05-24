package com.aiglass.app.camera

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy


class CameraManager(
    private val lifecycleOwner: LifecycleOwner,
    private val onFrameCaptured: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "CameraManager"
        private const val TARGET_WIDTH = 640
        private const val TARGET_HEIGHT = 480
        private const val JPEG_QUALITY = 75
        private const val TARGET_FPS = 10
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: androidx.camera.core.Camera? = null
    private var lastFrameTime = 0L

    fun start(context: Context) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val jpegBytes = imageProxyToJpeg(imageProxy)
                    if (jpegBytes != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime >= 1000 / TARGET_FPS) {
                            lastFrameTime = now
                            onFrameCaptured(jpegBytes)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Frame capture error: ${e.message}")
                } finally {
                    imageProxy.close()
                }
            }

            try {
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                Log.i(TAG, "Camera bound to lifecycle")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        return try {
            val nv21 = yuv420888ToNv21(imageProxy)

            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                70,
                out
            )

            out.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun yuv420888ToNv21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height
        val ySize = width * height
        val nv21 = ByteArray(width * height * 3 / 2)

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        var pos = 0

        for (row in 0 until height) {
            for (col in 0 until width) {
                val index = row * yRowStride + col * yPixelStride
                nv21[pos++] = yBuffer.get(index)
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        var uvPos = ySize

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride

                // NV21 是 VU 顺序
                nv21[uvPos++] = vBuffer.get(vIndex)
                nv21[uvPos++] = uBuffer.get(uIndex)
            }
        }

        return nv21
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21, ImageFormat.NV21,
            imageProxy.width, imageProxy.height, null
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            JPEG_QUALITY, out
        )
        val jpegData = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
    }

    fun stop() {
        cameraExecutor.shutdown()
        imageAnalysis?.clearAnalyzer()
        Log.i(TAG, "Camera stopped")
    }
}
