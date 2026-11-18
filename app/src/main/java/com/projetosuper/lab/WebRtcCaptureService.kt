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
import android.os.IBinder
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
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcCaptureService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.projetosuper.lab.WEBRTC_START"
        const val ACTION_STOP = "com.projetosuper.lab.WEBRTC_STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "projeto_super_webrtc"
        private const val NOTIFICATION_ID = 4103
    }

    private var sessionId = ""
    private var mode = ""
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var egl: EglBase? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var lastSignalId = 0L
    private var remoteDescriptionReady = false
    private val stopping = AtomicBoolean(false)
    private val pendingIce = mutableListOf<IceCandidate>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val startIntent = intent
        if (startIntent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (startIntent.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        sessionId = startIntent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        mode = startIntent.getStringExtra(EXTRA_MODE).orEmpty()
        if (sessionId.isBlank() || mode !in listOf("camera_front", "camera_back", "screen_share")) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (mode != "screen_share" &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = startIntent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            startIntent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            startIntent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (mode == "screen_share" && (resultCode == 0 || resultData == null)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startVisibleForeground()
        try {
            initializeWebRtc()
            startLocalVideo(resultCode, resultData)
            createPeer()
            startSignalPolling()
            startStopWatcher()
        } catch (error: Exception) {
            android.util.Log.e("ProjetoSuperWebRTC", "Falha ao iniciar WebRTC", error)
            stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startVisibleForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transmissão WebRTC", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 4106, Intent(this, WebRtcCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = if (mode == "screen_share") "tela" else "câmera"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (mode == "screen_share") android.R.drawable.ic_menu_view else android.R.drawable.ic_menu_camera)
            .setContentTitle("Projeto Super — transmissão ativa")
            .setContentText("Transmissão de $label autorizada e visível. Toque em Parar para encerrar.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Parar transmissão", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            if (mode == "screen_share") ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun initializeWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        egl = EglBase.create()
        val encoder = DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(egl!!.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
    }

    private fun startLocalVideo(resultCode: Int, resultData: Intent?) {
        val screen = mode == "screen_share"
        videoSource = factory!!.createVideoSource(screen)
        textureHelper = SurfaceTextureHelper.create("ProjetoSuperVideoCapture", egl!!.eglBaseContext)
        capturer = if (screen) {
            ScreenCapturerAndroid(resultData!!, object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            })
        } else {
            createCameraCapturer(mode == "camera_front")
        }
        val localCapturer = capturer ?: error("Não foi possível encontrar a câmera deste aparelho.")
        localCapturer.initialize(textureHelper!!, applicationContext, videoSource!!.capturerObserver)
        if (screen) localCapturer.startCapture(960, 540, 20)
        else localCapturer.startCapture(640, 360, 20)
        videoTrack = factory!!.createVideoTrack("projeto-super-video", videoSource)
        videoTrack?.setEnabled(true)
    }

    private fun createCameraCapturer(front: Boolean): VideoCapturer? {
        val enumerator = if (Camera2Enumerator.isSupported(this)) Camera2Enumerator(this) else Camera1Enumerator(false)
        val names = enumerator.deviceNames
        val selected = names.firstOrNull { if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
            ?: names.firstOrNull()
        return selected?.let { enumerator.createCapturer(it, null as CameraVideoCapturer.CameraEventsHandler?) }
    }

    private fun createPeer() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                android.util.Log.i("ProjetoSuperWebRTC", "ICE: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        ControlApi.sendWebRtcSignal(this@WebRtcCaptureService, sessionId, "ice",
                            JSONObject().put("sdpMid", candidate.sdpMid)
                                .put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp))
                    } catch (error: Exception) {
                        android.util.Log.w("ProjetoSuperWebRTC", "Falha ao enviar ICE: ${error.message}")
                    }
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: error("Não foi possível criar a conexão WebRTC.")
        peer!!.addTrack(videoTrack, Collections.singletonList("projeto-super-stream"))
        peer!!.senders.firstOrNull()?.let { sender ->
            val parameters = sender.parameters
            parameters.encodings.forEach { encoding ->
                encoding.maxBitrateBps = 1_200_000
                encoding.maxFramerate = 20
                encoding.scaleResolutionDownBy = 1.0
            }
            sender.setParameters(parameters)
        }
    }

    private fun startSignalPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !stopping.get()) {
                try {
                    val response = ControlApi.getWebRtcSignals(this@WebRtcCaptureService, sessionId, lastSignalId)
                    val signals = response.optJSONArray("signals")
                    if (signals != null) {
                        for (i in 0 until signals.length()) {
                            val signal = signals.getJSONObject(i)
                            lastSignalId = maxOf(lastSignalId, signal.optLong("id"))
                            when (signal.optString("signal_type")) {
                                "offer" -> handleOffer(signal.getJSONObject("payload"))
                                "ice" -> handleRemoteIce(signal.getJSONObject("payload"))
                                "state" -> if (signal.optJSONObject("payload")?.optString("state") == "stop") stopCapture()
                            }
                        }
                    }
                } catch (error: Exception) {
                    android.util.Log.w("ProjetoSuperWebRTC", "Sinalização temporariamente indisponível: ${error.message}")
                }
                delay(700)
            }
        }
    }

    private fun handleOffer(payload: JSONObject) {
        val sdp = payload.optString("sdp")
        if (sdp.isBlank() || peer == null) return
        peer!!.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                remoteDescriptionReady = true
                synchronized(pendingIce) {
                    pendingIce.forEach { peer?.addIceCandidate(it) }
                    pendingIce.clear()
                }
                peer?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return
                        peer?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onSetSuccess() {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        ControlApi.sendWebRtcSignal(this@WebRtcCaptureService, sessionId, "answer",
                                            JSONObject().put("type", answer.type.canonicalForm()).put("sdp", answer.description))
                                    } catch (error: Exception) {
                                        android.util.Log.e("ProjetoSuperWebRTC", "Falha ao enviar resposta SDP", error)
                                    }
                                }
                            }
                            override fun onCreateFailure(error: String?) { android.util.Log.e("ProjetoSuperWebRTC", error.orEmpty()) }
                            override fun onSetFailure(error: String?) { android.util.Log.e("ProjetoSuperWebRTC", error.orEmpty()) }
                        }, answer)
                    }
                    override fun onCreateFailure(error: String?) { android.util.Log.e("ProjetoSuperWebRTC", error.orEmpty()) }
                    override fun onSetSuccess() {}
                }, MediaConstraints())
            }
            override fun onCreateFailure(error: String?) { android.util.Log.e("ProjetoSuperWebRTC", error.orEmpty()) }
            override fun onSetFailure(error: String?) { android.util.Log.e("ProjetoSuperWebRTC", error.orEmpty()) }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun handleRemoteIce(payload: JSONObject) {
        val candidate = IceCandidate(
            payload.optString("sdpMid"),
            payload.optInt("sdpMLineIndex"),
            payload.optString("candidate")
        )
        if (candidate.sdp.isBlank()) return
        if (remoteDescriptionReady) peer?.addIceCandidate(candidate)
        else synchronized(pendingIce) { pendingIce.add(candidate) }
    }

    private fun startStopWatcher() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !stopping.get()) {
                try {
                    val response = ControlApi.poll(this@WebRtcCaptureService)
                    val requests = response.optJSONArray("requests")
                    if (requests != null) {
                        for (i in 0 until requests.length()) {
                            val request = requests.getJSONObject(i)
                            if (request.optString("action") == "stop_session") {
                                ControlApi.decide(this@WebRtcCaptureService, request.optString("id"), true)
                                stopCapture(remoteAlreadyStopped = true)
                                return@launch
                            }
                        }
                    }
                } catch (_: Exception) {}
                delay(2500)
            }
        }
    }

    private fun stopCapture(remoteAlreadyStopped: Boolean = false) {
        if (!stopping.compareAndSet(false, true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try { ControlApi.sendWebRtcSignal(this@WebRtcCaptureService, sessionId, "state", JSONObject().put("state", "stop")) }
            catch (_: Exception) {}
            if (!remoteAlreadyStopped && sessionId.isNotBlank()) {
                try { ControlApi.stopSession(this@WebRtcCaptureService, sessionId) } catch (_: Exception) {}
            }
        }
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        peer?.close()
        peer?.dispose()
        peer = null
        textureHelper?.dispose()
        textureHelper = null
        factory?.dispose()
        factory = null
        egl?.release()
        egl = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping.get()) {
            try { capturer?.stopCapture() } catch (_: Exception) {}
            try { capturer?.dispose() } catch (_: Exception) {}
            peer?.close()
            peer?.dispose()
            videoTrack?.dispose()
            videoSource?.dispose()
            textureHelper?.dispose()
            factory?.dispose()
            egl?.release()
        }
        super.onDestroy()
    }
}