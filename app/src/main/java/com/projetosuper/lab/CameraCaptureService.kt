package com.projetosuper.lab

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class CameraCaptureService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.projetosuper.lab.CAMERA_START"
        const val ACTION_STOP = "com.projetosuper.lab.CAMERA_STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_LENS = "lens"
        private const val CHANNEL_ID = "projeto_super_camera"
        private const val NOTIFICATION_ID = 4101
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var sessionId: String = ""
    private val uploading = AtomicBoolean(false)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var starting = false
    @Volatile private var stopping = false
    @Volatile private var lastFrameAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        val incomingSessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (starting || cameraProvider != null || stopping) {
            android.util.Log.w("ProjetoSuperCamera", "Solicitação duplicada de câmera ignorada.")
            if (incomingSessionId.isNotBlank() && incomingSessionId != sessionId) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try { ControlApi.stopSession(this@CameraCaptureService, incomingSessionId) } catch (_: Exception) {}
                }
            }
            return START_NOT_STICKY
        }
        sessionId = incomingSessionId
        val lens = intent?.getStringExtra(EXTRA_LENS) ?: "back"
        if (sessionId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopCapture()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val stopIntent = Intent(this, CameraCaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 4102, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Projeto Super — câmera ativa")
            .setContentText("Transmissão de quadros autorizada. Toque em Parar para encerrar.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopPending)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        starting = true
        startCamera(lens)
        startStopCommandWatcher()
        return START_NOT_STICKY
    }

    private fun startCamera(lens: String) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (stopping) {
                    provider.unbindAll()
                    return@addListener
                }
                cameraProvider = provider
                provider.unbindAll()
                val selector = if (lens == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(android.util.Size(640, 480), FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyzeFrame(image)
                }
                provider.bindToLifecycle(this, selector, analysis)
                starting = false
            } catch (error: Exception) {
                starting = false
                android.util.Log.e("ProjetoSuperCamera", "Falha ao iniciar câmera", error)
                stopCapture()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (stopping) {
            image.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < 1000 || !uploading.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt = now
        val jpeg = try { toJpeg(image) } catch (_: Exception) { null }
        image.close()
        if (jpeg == null) {
            uploading.set(false)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = ControlApi.uploadFrame(this@CameraCaptureService, sessionId, jpeg)
                android.util.Log.d(
                    "ProjetoSuperCamera",
                    "JPEG da câmera confirmado: ${jpeg.size} bytes; ${response.optString("received_at")}"
                )
            } catch (error: Exception) {
                android.util.Log.w("ProjetoSuperCamera", "Quadro não enviado: ${error.message}")
            } finally {
                uploading.set(false)
            }
        }
    }

    private fun toJpeg(image: ImageProxy): ByteArray? {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val yBuffer = yPlane.buffer
        var output = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) nv21[output++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
        }
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                nv21[output++] = vBuffer.get(vIndex)
                nv21[output++] = uBuffer.get(uIndex)
            }
        }
        val out = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        return if (yuv.compressToJpeg(Rect(0, 0, width, height), 65, out)) out.toByteArray() else null
    }

    private fun startStopCommandWatcher() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val response = ControlApi.poll(this@CameraCaptureService, "camera")
                    val requests = response.optJSONArray("requests")
                    if (requests != null) {
                        for (i in 0 until requests.length()) {
                            val request = requests.getJSONObject(i)
                            if (request.optString("action") == "stop_session") {
                                ControlApi.decide(this@CameraCaptureService, request.optString("id"), true)
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
        if (stopping) return
        stopping = true
        lifecycleScope.launch(Dispatchers.Main) {
            cameraProvider?.unbindAll()
            cameraProvider = null
            if (!remoteAlreadyStopped && sessionId.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    try { ControlApi.stopSession(this@CameraCaptureService, sessionId) } catch (_: Exception) {}
                }
            }
            ServiceCompat.stopForeground(this@CameraCaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sessão de câmera", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stopping = true
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
