package com.projetosuper.lab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.projetosuper.lab.SCREEN_START"
        const val ACTION_STOP = "com.projetosuper.lab.SCREEN_STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "projeto_super_screen"
        private const val NOTIFICATION_ID = 4102
    }

    private var sessionId = ""
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var lastFrameAt = 0L
    private val uploading = AtomicBoolean(false)
    @Volatile private var shuttingDown = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        val incomingSessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (projection != null) {
            if (incomingSessionId == sessionId) {
                android.util.Log.i("ProjetoSuperScreen", "Solicitação duplicada ignorada para a sessão ativa.")
            } else {
                android.util.Log.w("ProjetoSuperScreen", "Nova captura recusada enquanto outra sessão está ativa.")
            }
            return START_NOT_STICKY
        }
        sessionId = incomingSessionId
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (sessionId.isBlank() || resultData == null || resultCode == 0) {
            if (sessionId.isNotBlank()) stopCapture() else stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val stopPending = PendingIntent.getService(
            this, 4105, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Projeto Super — tela compartilhada")
            .setContentText("Compartilhamento ativo por autorização do Android. Toque em Parar para encerrar.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopPending)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        startProjection(resultCode, resultData)
        startStopCommandWatcher()
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = resources.displayMetrics
            val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
            val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
            val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageThread = HandlerThread("ProjetoSuperScreenFrames").also { it.start() }
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processImage(image)
            }, Handler(imageThread!!.looper))
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture(remoteAlreadyStopped = false) }
            }, Handler(mainLooper))
            val activeProjection = projection ?: error("O Android não concedeu uma sessão de captura válida.")
            virtualDisplay = activeProjection.createVirtualDisplay(
                "ProjetoSuperScreen", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            check(virtualDisplay != null) {
                "O Android não conseguiu criar a superfície de captura da tela."
            }
            android.util.Log.i(
                "ProjetoSuperScreen",
                "MediaProjection ativa: ${width}x${height}; aguardando quadros JPEG."
            )
        } catch (error: Exception) {
            android.util.Log.e("ProjetoSuperScreen", "Falha ao iniciar compartilhamento", error)
            stopCapture()
        }
    }

    private fun processImage(image: Image) {
        if (shuttingDown) {
            image.close()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFrameAt < 300 || !uploading.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt = now
        val jpeg = try { imageToJpeg(image) } catch (_: Exception) { null }
        image.close()
        if (jpeg == null) {
            uploading.set(false)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = ControlApi.uploadFrame(this@ScreenCaptureService, sessionId, jpeg)
                android.util.Log.d(
                    "ProjetoSuperScreen",
                    "JPEG confirmado pelo servidor: ${jpeg.size} bytes; ${response.optString("received_at")}"
                )
            } catch (error: Exception) {
                android.util.Log.w("ProjetoSuperScreen", "Quadro não enviado: ${error.message}", error)
                val reason = error.message.orEmpty()
                if (reason.contains("Dispositivo não autenticado") ||
                    reason.contains("Chave do dispositivo inválida") ||
                    reason.contains("Sessão não está ativa")) {
                    stopCapture(remoteAlreadyStopped = true)
                }
            } finally {
                uploading.set(false)
            }
        }
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val scale = minOf(1f, 800f / image.width.toFloat(), 640f / image.height.toFloat())
        val scaledWidth = (image.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (image.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, scaledWidth, scaledHeight, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 58, out)
        if (scaled !== cropped) scaled.recycle()
        cropped.recycle()
        padded.recycle()
        return out.toByteArray()
    }

    private fun startStopCommandWatcher() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !shuttingDown) {
                try {
                    val response = ControlApi.poll(this@ScreenCaptureService)
                    val requests = response.optJSONArray("requests")
                    if (requests != null) {
                        for (index in 0 until requests.length()) {
                            val request = requests.optJSONObject(index) ?: continue
                            if (request.optString("action") == "stop_session") {
                                ControlApi.decide(this@ScreenCaptureService, request.optString("id"), true)
                                stopCapture(remoteAlreadyStopped = true)
                                return@launch
                            }
                        }
                    }
                } catch (error: Exception) {
                    val reason = error.message.orEmpty()
                    if (reason.contains("Chave do dispositivo inválida") ||
                        reason.contains("Dispositivo não autenticado")) {
                        stopCapture(remoteAlreadyStopped = true)
                        return@launch
                    }
                    android.util.Log.w("ProjetoSuperScreen", "Falha ao verificar encerramento remoto: ${error.message}")
                }
                delay(3000)
            }
        }
    }

    private fun stopCapture(remoteAlreadyStopped: Boolean = false) {
        if (shuttingDown) return
        shuttingDown = true
        lifecycleScope.launch(Dispatchers.Main) {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            projection?.stop()
            projection = null
            imageThread?.quitSafely()
            imageThread = null
            if (!remoteAlreadyStopped && sessionId.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    try { ControlApi.stopSession(this@ScreenCaptureService, sessionId) } catch (_: Exception) {}
                }
            }
            ServiceCompat.stopForeground(this@ScreenCaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Compartilhamento de tela", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        imageThread?.quitSafely()
        super.onDestroy()
    }
}
