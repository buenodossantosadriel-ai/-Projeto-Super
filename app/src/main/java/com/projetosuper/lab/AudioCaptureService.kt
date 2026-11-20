package com.projetosuper.lab

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioCaptureService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.projetosuper.lab.AUDIO_START"
        const val ACTION_STOP = "com.projetosuper.lab.AUDIO_STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_MODE = "audio_mode"
        private const val CHANNEL_ID = "projeto_super_audio"
        private const val NOTIFICATION_ID = 4103
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_MS = 2000
    }

    @Volatile private var sessionId = ""
    @Volatile private var mode = "live"
    @Volatile private var stopping = false
    @Volatile private var starting = false
    @Volatile private var recorder: AudioRecord? = null
    private val uploading = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        val incomingSessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (starting || recorder != null || stopping) {
            android.util.Log.w("ProjetoSuperAudio", "Solicitação duplicada de microfone ignorada.")
            if (incomingSessionId.isNotBlank() && incomingSessionId != sessionId) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try { ControlApi.stopSession(this@AudioCaptureService, incomingSessionId) } catch (_: Exception) {}
                }
            }
            return START_NOT_STICKY
        }
        sessionId = incomingSessionId
        mode = intent?.getStringExtra(EXTRA_MODE) ?: "live"
        if (sessionId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (mode !in listOf("live", "recording") ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            stopCapture()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val stopIntent = Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 4104, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (mode == "recording") "Projeto Super — gravação ativa" else "Projeto Super — microfone ao vivo"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText("O microfone está ativo. Toque em Parar para encerrar.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopPending)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        starting = true
        startRecording()
        watchStopRequests()
        return START_NOT_STICKY
    }

    private fun startRecording() {
        lifecycleScope.launch(Dispatchers.IO) {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                stopCapture()
                return@launch
            }
            val chunkBytes = SAMPLE_RATE * 2 * CHUNK_MS / 1000
            val audio = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, chunkBytes * 2)
            )
            recorder = audio
            try {
                if (audio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Não foi possível inicializar o microfone.")
                if (stopping) return@launch
                audio.startRecording()
                starting = false
                var chunkIndex = 0L
                while (isActive && !stopping && audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val pcm = ByteArray(chunkBytes)
                    var offset = 0
                    while (offset < pcm.size && !stopping && isActive) {
                        val count = audio.read(pcm, offset, pcm.size - offset, AudioRecord.READ_BLOCKING)
                        if (count < 0) throw IllegalStateException("Falha ao ler o microfone.")
                        offset += count
                    }
                    if (offset == pcm.size && !stopping) {
                        val wav = makeWav(pcm, SAMPLE_RATE, 1, 16)
                        ControlApi.uploadAudioChunk(
                            this@AudioCaptureService, sessionId, wav, mode, chunkIndex++, CHUNK_MS
                        )
                    }
                }
            } catch (error: Exception) {
                android.util.Log.e("ProjetoSuperAudio", "Captura de áudio interrompida", error)
            } finally {
                try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() } catch (_: Exception) {}
                audio.release()
                recorder = null
                starting = false
                stopCapture()
            }
        }
    }

    private fun watchStopRequests() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !stopping) {
                try {
                    val response = ControlApi.poll(this@AudioCaptureService)
                    val requests = response.optJSONArray("requests")
                    if (requests != null) {
                        for (i in 0 until requests.length()) {
                            val request = requests.getJSONObject(i)
                            if (request.optString("action") == "stop_session") {
                                ControlApi.decide(this@AudioCaptureService, request.optString("id"), true)
                                stopCapture(remoteAlreadyStopped = true)
                                return@launch
                            }
                        }
                    }
                } catch (error: Exception) {
                    android.util.Log.w("ProjetoSuperAudio", "Falha ao verificar encerramento remoto: ${error.message}")
                }
                delay(3000)
            }
        }
    }

    private fun stopCapture(remoteAlreadyStopped: Boolean = false) {
        if (stopping) return
        stopping = true
        try { recorder?.stop() } catch (_: Exception) {}
        if (!remoteAlreadyStopped && sessionId.isNotBlank()) {
            // lifecycleScope seria cancelado pelo stopSelf antes de confirmar o encerramento remoto.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    ControlApi.stopSession(this@AudioCaptureService, sessionId)
                } catch (error: Exception) {
                    android.util.Log.e("ProjetoSuperAudio", "Não foi possível encerrar sessão no servidor: ${error.message}")
                } finally {
                    withContext(Dispatchers.Main) {
                        ServiceCompat.stopForeground(this@AudioCaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun makeWav(pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun ascii(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
        fun little(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff)
            out.write((value shr 24) and 0xff)
        }
        ascii("RIFF"); little(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); little(16); little(1); out.write(channels); out.write(0)
        out.write(sampleRate and 0xff); out.write((sampleRate shr 8) and 0xff)
        out.write((sampleRate shr 16) and 0xff); out.write((sampleRate shr 24) and 0xff)
        little(byteRate); out.write(channels * bits / 8); out.write(0); out.write(bits); out.write(0)
        ascii("data"); little(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sessões de áudio", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stopping = true
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        super.onDestroy()
    }
}
