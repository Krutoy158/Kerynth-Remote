package com.kerynth.remote.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.kerynth.remote.agent.START_SCREEN_CAPTURE"
        const val ACTION_STOP = "com.kerynth.remote.agent.STOP_SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "screen_result_code"
        const val EXTRA_RESULT_DATA = "screen_result_data"
        private const val CHANNEL_ID = "kerynth_screen_capture"
        private const val NOTIFICATION_ID = 43178
        private const val MAX_DIMENSION = 900

        @Volatile
        private var latestFrame: ByteArray? = null

        @Volatile
        private var frameWidth = 0

        @Volatile
        private var frameHeight = 0

        @Volatile
        private var active = false

        fun isActive(): Boolean = active

        fun currentFrame(): Pair<ByteArray, Pair<Int, Int>>? {
            val frame = latestFrame ?: return null
            return frame.copyOf() to (frameWidth to frameHeight)
        }
    }

    private val stopped = AtomicBoolean(false)
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY

        createNotificationChannel()
        startCaptureForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching {
            startCapture(resultCode, resultData)
        }.onFailure {
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCaptureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, resultData)
        projection = mediaProjection

        val thread = HandlerThread("KerynthScreenCapture").apply { start() }
        handlerThread = thread
        val captureHandler = Handler(thread.looper)
        handler = captureHandler

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = reader

        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                runCatching {
                    val plane = it.planes.first()
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val paddedWidth = rowStride / pixelStride
                    val fullBitmap = Bitmap.createBitmap(
                        paddedWidth,
                        it.height,
                        Bitmap.Config.ARGB_8888
                    )
                    buffer.rewind()
                    fullBitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, it.width, it.height)
                    if (cropped !== fullBitmap) fullBitmap.recycle()
                    val scale = minOf(
                        1.0,
                        MAX_DIMENSION.toDouble() / maxOf(cropped.width, cropped.height)
                    )
                    val output = if (scale < 1.0) {
                        Bitmap.createScaledBitmap(
                            cropped,
                            (cropped.width * scale).toInt().coerceAtLeast(1),
                            (cropped.height * scale).toInt().coerceAtLeast(1),
                            true
                        ).also { if (it !== cropped) cropped.recycle() }
                    } else {
                        cropped
                    }
                    val bytes = ByteArrayOutputStream().use { stream ->
                        output.compress(Bitmap.CompressFormat.JPEG, 48, stream)
                        stream.toByteArray()
                    }
                    output.recycle()
                    latestFrame = bytes
                    frameWidth = if (scale < 1.0) (width * scale).toInt() else width
                    frameHeight = if (scale < 1.0) (height * scale).toInt() else height
                }
            }
        }, captureHandler)

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
                stopSelf()
            }
        }
        projectionCallback = callback
        mediaProjection.registerCallback(callback, captureHandler)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "Kerynth Remote Screen",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
        active = true
    }

    private fun stopCapture() {
        if (!stopped.compareAndSet(false, true)) return
        active = false
        latestFrame = null
        frameWidth = 0
        frameHeight = 0
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        projectionCallback?.let { callback ->
            runCatching { projection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible notification while Kerynth Remote shares this screen"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Kerynth Remote: экран транслируется")
            .setContentText("Сопряжённая Console может видеть экран. Нажми «Остановить», чтобы завершить.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopIntent)
            .build()
    }
}
