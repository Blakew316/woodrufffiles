package com.woodruff.tvexplorer.nest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared WebRTC engine: one factory and one GL context for every camera tile. */
object Rtc {
    @Volatile private var factory: PeerConnectionFactory? = null
    val egl: EglBase by lazy { EglBase.create() }

    @Synchronized
    fun factory(c: Context): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(c.applicationContext).createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()
            .also { factory = it }
    }
}

/**
 * One live camera view. Google's live streams end after about five minutes, so this extends the
 * stream every four, and reconnects from scratch if extending or the connection fails.
 */
class CameraStream(private val context: Context, private val camera: Camera, private val sink: VideoSink) {
    sealed interface Status {
        data object Connecting : Status
        data object Live : Status
        data class Failed(val message: String) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status
    @Volatile private var pc: PeerConnection? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var audio: AudioTrack? = null
    @Volatile private var audioOn = false
    @Volatile private var dropped: CompletableDeferred<Unit>? = null
    @Volatile private var job: Job? = null
    @Volatile private var onClosed: (() -> Unit)? = null

    fun start() {
        job = scope.launch {
            try { run() } finally {
                // Only this coroutine ever touches the connection, so tearing down here (after any
                // connect step in progress has returned) can't race it. Closing from another thread
                // while connect() was still using the connection crashed the app natively.
                withContext(NonCancellable) {
                    val id = sessionId
                    sessionId = null
                    teardown()
                    if (id != null) runCatching { NestApi.stopStream(context, camera, id) }
                    onClosed?.invoke()
                }
            }
        }
    }

    private suspend fun run() {
        var failures = 0
        while (true) {
            try {
                connect()
                failures = 0
                // Keep the stream going; reconnect if the network drops it.
                val drop = CompletableDeferred<Unit>().also { dropped = it }
                while (true) {
                    val lost = withTimeoutOrNull(EXTEND_EVERY_MS) { drop.await() } != null
                    if (lost) throw IllegalStateException("Connection lost")
                    sessionId = NestApi.extendStream(context, camera, sessionId ?: break)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CameraStream", "${camera.label}: ${e.message}")
                failures++
                _status.value = Status.Failed(e.message ?: "Couldn't connect")
            }
            teardown()
            // Back off: 3 s, 6 s, … up to a minute, so a camera that's offline doesn't spam Google.
            delay(minOf(60_000L, 3_000L * failures.coerceAtLeast(1)))
            _status.value = Status.Connecting
        }
    }

    /** Synchronized with [teardown], so it never touches a track that's being disposed. */
    @Synchronized
    fun setAudio(on: Boolean) {
        audioOn = on
        audio?.setEnabled(on)
    }

    /**
     * Stops the stream; [then] runs (on a background thread) once the connection is fully torn
     * down, so the video renderer is only released after nothing can draw into it.
     */
    fun close(then: () -> Unit = {}) {
        onClosed = then
        val j = job
        if (j == null) then() else j.cancel()
    }

    private suspend fun connect() {
        val factory = Rtc.factory(context)
        val gathered = CompletableDeferred<Unit>()
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }

        val conn = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> _status.value = Status.Live
                    // DISCONNECTED often recovers by itself; FAILED doesn't.
                    PeerConnection.IceConnectionState.FAILED -> dropped?.complete(Unit)
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver?) {
                when (val track = transceiver?.receiver?.track()) {
                    is VideoTrack -> track.addSink(sink)
                    is AudioTrack -> { audio = track; track.setEnabled(audioOn) }
                    else -> Unit
                }
            }
        }) ?: throw IllegalStateException("WebRTC isn't available on this TV")
        pc = conn

        // Google requires audio, then video, then a data channel in the offer, in that order.
        val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
        conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        conn.createDataChannel("dataSendChannel", DataChannel.Init())

        val offer = sdp { obs -> conn.createOffer(obs, MediaConstraints()) }
        setDescription { obs -> conn.setLocalDescription(obs, offer) }
        // Include our network candidates in the offer (Google doesn't take trickled ones).
        withTimeoutOrNull(3_000) { gathered.await() }
        val local = conn.localDescription?.description ?: offer.description

        val session = NestApi.startStream(context, camera, local)
        sessionId = session.mediaSessionId
        setDescription { obs -> conn.setRemoteDescription(obs, SessionDescription(SessionDescription.Type.ANSWER, session.answerSdp)) }
    }

    /** Synchronized: [close] and the reconnect loop may both tear down; a double dispose crashes natively. */
    @Synchronized
    private fun teardown() {
        dropped = null
        audio = null
        val p = pc ?: return
        pc = null
        runCatching { p.close() }
        runCatching { p.dispose() }
    }

    private suspend fun sdp(start: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        start(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) cont.resume(desc) else cont.resumeWithException(IllegalStateException("No offer"))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException(error ?: "Offer failed"))
            override fun onSetFailure(error: String?) {}
        })
    }

    private suspend fun setDescription(start: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        start(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException(error ?: "Camera answer rejected"))
        })
    }

    companion object {
        private const val EXTEND_EVERY_MS = 4 * 60_000L
    }
}
