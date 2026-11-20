package com.projetosuper.lab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.pm.ServiceInfo
import android.os.Build
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mantém a sessão de captura em primeiro plano e envia quadros JPEG obtidos
 * pelo RemoteControlAccessibilityService.takeScreenshot().
 * Não cria display virtual, não usa MediaProjection e não usa WebRTC.
 */
class ScreenCaptureService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.projetosuper.lab.SCREEN_START"
        const val ACTION_STOP = "com.projetosuper.lab.SCREEN_STOP"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "projeto_super_screen"
        private const val NOTIFICATION_ID = 4102
        private const val FRAME_INTERVAL_MS = 750L
    }

    private var sessionId = ""
    private val uploading = AtomicBoolean(false)
    @Volatile private var shuttingDown = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        val incomingSessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (sessionId.isNotBlank() && !shuttingDown) {
            if (incomingSessionId.isNotBlank() && incomingSessionId != sessionId) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try { ControlApi.stopSession(this@ScreenCaptureService, incomingSessionId) } catch (_: Exception) {}
                }
            }
            return START_NOT_STICKY
        }

        sessionId = incomingSessionId
        shuttingDown = false
        if (sessionId.isBlank() || !remoteControlReady()) {
            if (sessionId.isNotBlank()) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try { ControlApi.stopSession(this@ScreenCaptureService, sessionId) } catch (_: Exception) {}
                }
            }
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
            .setContentTitle("Projeto Super — captura de tela")
            .setContentText("Enviando quadros JPEG da tela autorizada. Toque em Parar para encerrar.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopPending)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        startFrameUploadLoop()
        startStopCommandWatcher()
        return START_NOT_STICKY
    }

    private fun startFrameUploadLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !shuttingDown) {
                if (!remoteControlReady()) {
                    stopCapture()
                    return@launch
                }
                val accessibility = RemoteControlAccessibilityService.activeInstance
                if (accessibility == null) {
                    android.util.Log.w("ProjetoSuperScreen", "Aguardando Serviço de Acessibilidade ficar disponível.")
                    delay(500)
                    continue
                }
                if (uploading.compareAndSet(false, true)) {
                    try {
                        val jpeg = accessibility.captureJpegFrame()
                        if (jpeg != null && jpeg.isNotEmpty() && !shuttingDown) {
                            ControlApi.uploadFrame(this@ScreenCaptureService, sessionId, jpeg)
                        }
                    } catch (error: Exception) {
                        android.util.Log.w("ProjetoSuperScreen", "Quadro JPEG não enviado: ${error.message}")
                    } finally {
                        uploading.set(false)
                    }
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun remoteControlReady(): Boolean {
        val prefs = getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("app_control_authorized", false) || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val component = ComponentName(this, RemoteControlAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').contains(component.flattenToString())
    }

    private fun startStopCommandWatcher() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !shuttingDown) {
                if (!remoteControlReady()) {
                    stopCapture()
                    return@launch
                }
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
        getSharedPreferences("projeto_super_device", Context.MODE_PRIVATE)
            .edit()
            .remove(RemoteDisplayLauncher.PREF_DISPLAY_ID)
            .remove(RemoteDisplayLauncher.PREF_DISPLAY_WIDTH)
            .remove(RemoteDisplayLauncher.PREF_DISPLAY_HEIGHT)
            .remove("remote_target_package")
            .apply()
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
            .remove("remote_target_package")
            .apply()
        shuttingDown = true
        super.onDestroy()
    }
}
