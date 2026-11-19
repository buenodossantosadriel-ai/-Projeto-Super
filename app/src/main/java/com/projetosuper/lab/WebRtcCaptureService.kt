package com.projetosuper.lab

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** WebRTC media uses Metered STUN/TURN; Supabase carries signaling only. */
class WebRtcCaptureService : LifecycleService() {
    companion object {
        const val ACTION_START_CAMERA = "com.projetosuper.lab.WEBRTC_CAMERA_START"
        const val ACTION_START_SCREEN = "com.projetosuper.lab.WEBRTC_SCREEN_START"
        const val ACTION_STOP = "com.projetosuper.lab.WEBRTC_STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_LENS = "lens"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "projeto_super_webrtc"
        private const val NOTIFICATION_ID = 4110
        @Volatile private var factoryInitialized = false

        private fun initializeFactory(context: Context) {
            if (!factoryInitialized) synchronized(this) {
                if (!factoryInitialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
                    )
                    factoryInitialized = true
                }
            }
        }
    }

    private var sessionId = ""
    private var mode = "camera"
    private var lens = "back"
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    @Volatile private var shuttingDown = false
    private var lastSignalId = 0L
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        mode = if (intent?.action == ACTION_START_SCREEN) "screen" else "camera"
        lens = intent?.getStringExtra(EXTRA_LENS) ?: "back"
        if (sessionId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (mode == "camera" &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val stopPending = PendingIntent.getService(
            this, 4111, Intent(this, WebRtcCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (mode == "camera") android.R.drawable.ic_menu_camera else android.R.drawable.ic_menu_view)
            .setContentTitle(if (mode == "camera") "Projeto Super — câmera WebRTC" else "Projeto Super — tela WebRTC")
            .setContentText("Vídeo direto ativo após sua autorização. Toque em Parar para encerrar.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopPending)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= 29) {
            if (mode == "camera") ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        lifecycleScope.launch {
            try {
                startWebRtc(intent)
            } catch (error: Exception) {
                android.util.Log.e("ProjetoSuperWebRTC", "Falha ao iniciar WebRTC", error)
                stopCapture()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startWebRtc(intent: Intent?) {
        initializeFactory(this)
        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
        val iceConfig = ControlApi.getWebRtcIceServers(this, sessionId)
        val iceArray = iceConfig.optJSONArray("iceServers")
            ?: error("O servidor não retornou a lista ICE.")
        val iceServers = (0 until iceArray.length()).flatMap { index ->
            val item = iceArray.getJSONObject(index)
            val urls = item.optJSONArray("urls")
            val urlList = if (urls != null) {
                (0 until urls.length()).map { urls.getString(it) }
            } else listOf(item.getString("urls"))
            val username = item.optString("username").takeIf { it.isNotBlank() }
            val credential = item.optString("credential").takeIf { it.isNotBlank() }
            urlList.map { url ->
                val builder = PeerConnection.IceServer.builder(url)
                if (!url.startsWith("stun:", ignoreCase = true)) {
                    check(!username.isNullOrBlank() && !credential.isNullOrBlank()) {
                        "Credenciais TURN incompletas para $url."
                    }
                    builder.setUsername(username!!).setPassword(credential!!)
                }
                builder.createIceServer()
            }
        }
        check(iceServers.isNotEmpty()) { "A lista ICE está vazia." }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = factory!!.createPeerConnection(rtcConfig, peerObserver)
            ?: error("Não foi possível criar PeerConnection.")
        // ScreenCapturerAndroid precisa que a VideoSource seja marcada como screencast.
        videoSource = factory!!.createVideoSource(mode == "screen")
        textureHelper = SurfaceTextureHelper.create("ProjetoSuperCapture", eglContext)
        capturer = createCapturer(intent)
        capturer!!.initialize(textureHelper, applicationContext, videoSource!!.capturerObserver)
        videoTrack = factory!!.createVideoTrack("super-video-$sessionId", videoSource)
        videoTrack!!.setEnabled(true)
        peerConnection!!.addTrack(videoTrack, listOf("super-stream-$sessionId"))
        // Vincula a faixa ao PeerConnection antes de iniciar a captura dos primeiros quadros.
        if (mode == "camera") {
            capturer!!.startCapture(640, 480, 20)
        } else {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            check(resultCode != 0) { "Autorização de compartilhamento de tela ausente." }
            capturer!!.startCapture(1280, 720, 15)
        }
        createOffer()
        startSignalPolling()
    }

    private fun createCapturer(intent: Intent?): VideoCapturer {
        if (mode == "screen") {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            } ?: error("Dados da autorização de tela ausentes.")
            check(resultCode != 0) { "Compartilhamento de tela não autorizado." }
            return ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            })
        }
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(this)) {
            Camera2Enumerator(this)
        } else Camera1Enumerator(true)
        val deviceName = enumerator.deviceNames.firstOrNull {
            if (lens == "front") enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: error("Câmera solicitada não encontrada.")
        return enumerator.createCapturer(deviceName, null) ?: error("Não foi possível abrir a câmera.")
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            android.util.Log.i("ProjetoSuperWebRTC", "ICE state=$state session=$sessionId")
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED
            ) stopCapture()
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            android.util.Log.i("ProjetoSuperWebRTC", "PeerConnection state=$state session=$sessionId")
            if (state == PeerConnection.PeerConnectionState.FAILED ||
                state == PeerConnection.PeerConnectionState.CLOSED
            ) stopCapture()
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            android.util.Log.i("ProjetoSuperWebRTC", "ICE gathering=$state session=$sessionId")
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            val payload = JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ControlApi.sendWebRtcSignal(this@WebRtcCaptureService, sessionId, "candidate", payload)
                } catch (error: Exception) {
                    android.util.Log.w("ProjetoSuperWebRTC", "ICE local não enviado: ${error.message}")
                }
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel) { channel.close() }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {}
    }

    private fun createOffer() {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val payload = JSONObject()
                            .put("type", description.type.canonicalForm())
                            .put("sdp", description.description)
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                ControlApi.sendWebRtcSignal(this@WebRtcCaptureService, sessionId, "offer", payload)
                            } catch (error: Exception) {
                                android.util.Log.e("ProjetoSuperWebRTC", "Offer não enviado", error)
                                stopCapture()
                            }
                        }
                    }
                    override fun onSetFailure(error: String) {
                        android.util.Log.e("ProjetoSuperWebRTC", "Falha ao definir SDP local: $error")
                        stopCapture()
                    }
                    override fun onCreateSuccess(description: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                android.util.Log.e("ProjetoSuperWebRTC", "Falha ao criar Offer: $error")
                stopCapture()
            }
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    private fun startSignalPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !shuttingDown) {
                try {
                    val response = ControlApi.pollWebRtcSignals(this@WebRtcCaptureService, sessionId, lastSignalId)
                    val signals = response.optJSONArray("signals")
                    if (signals != null) {
                        for (i in 0 until signals.length()) {
                            val signal = signals.getJSONObject(i)
                            lastSignalId = maxOf(lastSignalId, signal.optLong("id"))
                            when (signal.optString("signal_type")) {
                                "answer" -> applyRemoteDescription(signal.getJSONObject("payload"))
                                "candidate" -> addRemoteCandidate(signal.getJSONObject("payload"))
                                "hangup" -> {
                                    stopCapture(remoteAlreadyStopped = true)
                                    return@launch
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    android.util.Log.w("ProjetoSuperWebRTC", "Polling de sinalização: ${error.message}")
                }
                delay(700)
            }
        }
    }

    private fun applyRemoteDescription(payload: JSONObject) {
        val sdp = payload.optString("sdp")
        if (sdp.isBlank() || peerConnection?.remoteDescription != null) return
        val type = SessionDescription.Type.fromCanonicalForm(payload.optString("type", "answer"))
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                synchronized(pendingRemoteCandidates) {
                    pendingRemoteCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingRemoteCandidates.clear()
                }
            }
            override fun onSetFailure(error: String) {
                android.util.Log.e("ProjetoSuperWebRTC", "Answer inválida: $error")
                stopCapture()
            }
            override fun onCreateSuccess(description: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, SessionDescription(type, sdp))
    }

    private fun addRemoteCandidate(payload: JSONObject) {
        val candidateText = payload.optString("candidate")
        if (candidateText.isBlank()) return
        val candidate = IceCandidate(
            payload.optString("sdpMid").ifBlank { "0" },
            payload.optInt("sdpMLineIndex"),
            candidateText
        )
        if (peerConnection?.remoteDescription == null) {
            synchronized(pendingRemoteCandidates) { pendingRemoteCandidates.add(candidate) }
        } else {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    private fun stopCapture(remoteAlreadyStopped: Boolean = false) {
        if (shuttingDown) return
        shuttingDown = true
        if (!remoteAlreadyStopped && sessionId.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ControlApi.sendWebRtcSignal(this@WebRtcCaptureService, sessionId, "hangup", JSONObject())
                    ControlApi.stopSession(this@WebRtcCaptureService, sessionId)
                } catch (_: Exception) {}
            }
        }
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        capturer = null
        videoTrack?.dispose()
        videoTrack = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        videoSource?.dispose()
        videoSource = null
        textureHelper?.dispose()
        textureHelper = null
        factory?.dispose()
        factory = null
        eglBase?.release()
        eglBase = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transmissão WebRTC", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        if (!shuttingDown) stopCapture(remoteAlreadyStopped = true)
        super.onDestroy()
    }
}
