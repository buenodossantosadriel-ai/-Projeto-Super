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
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.projetosuper.lab.SCREEN_START"
        const val ACTION_STOP = "com.projetosuper.lab.SCREEN_STOP"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "projeto_super_screen"
        private const val NOTIFICATION_ID = 4102
    }

    private var sessionId = ""
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var lastFrameAt = 0L
    private val uploading = AtomicBoolean(false)
    private var shuttingDown = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        val incomingSessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (virtualDisplay != null || imageReader != null || shuttingDown) {
            android.util.Log.w("ProjetoSuperScreen", "Solicitação duplicada de display virtual ignorada.")
            if (incomingSessionId.isNotBlank() && incomingSessionId != sessionId) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try { ControlApi.stopSession(this@ScreenCaptureService, incomingSessionId) } catch (_: Exception) {}
                }
            }
            return START_NOT_STICKY
        }
        sessionId = incomingSessionId
        if (sessionId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val stopPending = PendingIntent.getService(
            this, 4105, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Projeto Super — ambiente remoto")
            .setContentText("Display virtual independente ativo. Toque em Parar para encerrar.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopPending)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        startVirtualDisplay()
        startStopCommandWatcher()
        return START_NOT_STICKY
    }

    /**
     * Cria um display próprio do app. Não usa MediaProjection nem espelha o display
     * físico padrão. Os apps precisam ser iniciados explicitamente neste display.
     */
    private fun startVirtualDisplay() {
        try {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "O controle isolado exige Android 11 ou superior."
            }
            check(packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
                "Este Android não declara suporte a atividades em displays secundários."
            }
            val metrics = resources.displayMetrics
            val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
            val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
            val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageThread = HandlerThread("ProjetoSuperVirtualDisplayFrames").also { it.start() }
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processImage(image)
            }, Handler(imageThread!!.looper))

            // Usa somente flags públicas. Os gestos são enviados pela API pública
            // GestureDescription.Builder.setDisplayId (Android 11+).
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            virtualDisplay = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .createVirtualDisplay(
                    "ProjetoSuperRemoteDisplay", width, height, metrics.densityDpi,
                    imageReader!!.surface, flags, null, Handler(mainLooper)
                )
            val createdDisplay = virtualDisplay?.display
                ?: error("O Android não criou o display virtual independente.")
            check(createdDisplay.displayId != android.view.Display.DEFAULT_DISPLAY) {
                "O Android não criou um display separado da tela física."
            }
            check(createdDisplay.flags and android.view.Display.FLAG_PRIVATE == 0) {
                "O Android criou um display privado; apps externos não podem ser iniciados nele."
            }
            getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
                .edit()
                .putInt(RemoteDisplayLauncher.PREF_DISPLAY_ID, createdDisplay.displayId)
                .putInt(RemoteDisplayLauncher.PREF_DISPLAY_WIDTH, width)
                .putInt(RemoteDisplayLauncher.PREF_DISPLAY_HEIGHT, height)
                .apply()
            android.util.Log.i("ProjetoSuperScreen", "Display virtual independente criado: id=${createdDisplay.displayId}, ${width}x${height}")
        } catch (error: Exception) {
            android.util.Log.e("ProjetoSuperScreen", "Falha ao criar display virtual independente", error)
            stopCapture()
        }
    }

    private fun processImage(image: Image) {
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
            try { ControlApi.uploadFrame(this@ScreenCaptureService, sessionId, jpeg) }
            catch (error: Exception) { android.util.Log.w("ProjetoSuperScreen", "Quadro não enviado: ${error.message}") }
            finally { uploading.set(false) }
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
        val targetWidth = minOf(800, image.width)
        val targetHeight = (image.height * (targetWidth.toFloat() / image.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
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
                    val response = ControlApi.poll(this@ScreenCaptureService, "screen")
                    val requests = response.optJSONArray("requests")
                    if (requests != null) {
                        for (i in 0 until requests.length()) {
                            val request = requests.getJSONObject(i)
                            if (request.optString("action") == "stop_session") {
                                ControlApi.decide(this@ScreenCaptureService, request.optString("id"), true)
                                stopCapture(remoteAlreadyStopped = true)
                                return@launch
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error.message?.contains("Chave do dispositivo inválida") == true) {
                        stopCapture(remoteAlreadyStopped = true)
                        return@launch
                    }
                }
                delay(3000)
            }
        }
    }

    private fun stopCapture(remoteAlreadyStopped: Boolean = false) {
        if (shuttingDown) return
        shuttingDown = true
        virtualDisplay?.release()
        virtualDisplay = null
        getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
            .edit()
            .remove(RemoteDisplayLauncher.PREF_DISPLAY_ID)
            .remove(RemoteDisplayLauncher.PREF_DISPLAY_WIDTH)
            .remove(RemoteDisplayLauncher.PREF_DISPLAY_HEIGHT)
            .apply()
        imageReader?.close()
        imageReader = null
        imageThread?.quitSafely()
        imageThread = null
        if (!remoteAlreadyStopped && sessionId.isNotBlank()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { ControlApi.stopSession(this@ScreenCaptureService, sessionId) }
                catch (error: Exception) {
                    android.util.Log.e("ProjetoSuperScreen", "Não foi possível encerrar a sessão no servidor: ${error.message}")
                }
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Compartilhamento de tela", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
            .edit()
            .remove(RemoteDisplayLauncher.PREF_DISPLAY_ID)
            .remove(RemoteDisplayLauncher.PREF_DISPLAY_WIDTH)
            .remove(RemoteDisplayLauncher.PREF_DISPLAY_HEIGHT)
            .apply()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        imageThread?.quitSafely()
        imageThread = null
        super.onDestroy()
    }
}
