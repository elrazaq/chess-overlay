package com.chess.overlay.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Helper perekam tangkapan layar on-demand (snapshot mode).
 * Jauh lebih hemat baterai & RAM daripada streaming video terus menerus.
 */
class ScreenCaptureHelper(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 1920
    private var screenDensity: Int = DisplayMetrics.DENSITY_DEFAULT

    init {
        setupMetrics()
        setupVirtualDisplay()
    }

    private fun setupMetrics() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupVirtualDisplay() {
        try {
            // Wajib didaftarkan pada Android 14+ sebelum memanggil createVirtualDisplay
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))

            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ChessCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    /**
     * Mengambil 1 lembar tangkapan layar (snapshot) saat tombol SCAN ditekan.
     */
    suspend fun captureSnapshot(): Bitmap? = withContext(Dispatchers.Default) {
        var image: Image? = null
        try {
            // Coba ambil frame terbaru dengan cepat
            for (attempt in 0..2) {
                image = imageReader?.acquireLatestImage() ?: imageReader?.acquireNextImage()
                if (image != null) break
                delay(25)
            }
            if (image == null) return@withContext null

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop jika ada padding row stride
            if (rowPadding == 0) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            image?.close()
        }
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection.stop()
    }
}
