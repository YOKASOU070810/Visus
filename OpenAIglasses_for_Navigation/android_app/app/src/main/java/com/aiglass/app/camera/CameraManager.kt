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
        // Prefer RGBA_8888 path if planes allow direct JPEG compression
        if (imageProxy.format == ImageFormat.JPEG || imageProxy.format == ImageFormat.YUV_420_888) {
            return try {
                val bitmap = imageProxyToBitmap(imageProxy) ?: return null
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                bitmap.recycle()
                out.toByteArray()
            } catch (e: Exception) {
                Log.e(TAG, "JPEG conversion error: ${e.message}")
                null
            }
        }
        return null
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
