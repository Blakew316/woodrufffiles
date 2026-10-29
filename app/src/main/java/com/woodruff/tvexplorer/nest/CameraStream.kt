package com.woodruff.tvexplorer.nest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * One camera's live stream, shown by any number of views at once ([CameraStreams] shares it): each
 * view [attach]es its picture and [detach]es it, and the stream runs from [start] until [close].
 * Google's live streams end after about five minutes, so this extends the stream every four, and
 * reconnects from scratch if extending or the connection fails.
 */
class CameraStream(private val context: Context, private val camera: Camera) {
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
    @Volatile private var audioOn = false
    @Volatile private var dropped: CompletableDeferred<Unit>? = null
    @Volatile private var job: Job? = null
    @Volatile private var onClosed: (() -> Unit)? = null

    // The views' pictures (each with whether it wants sound) and the current connection's tracks,
    // guarded by [tracks] alone, never this object's monitor: onTrack runs on WebRTC's signaling
    // thread and takes this lock, while teardown() and setAudio() hold the monitor and wait on that
    // same thread (closing the connection, switching the sound), so one lock for both would deadlock.
    private val tracks = Any()
    private val sinks = LinkedHashMap<VideoSink, Boolean>()
    /** The connection whose tracks these are; onTrack from an older one is ignored. */
    private var live: Any? = null
    private var video: VideoTrack? = null
    private var audio: AudioTrack? = null

    fun start() {
        if (job != null) return
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
                Gate.awaitTurn()
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
                if (e is RateLimited) Gate.limited()
                _status.value = Status.Failed(e.message ?: "Couldn't connect")
            }
            teardown()
            // Back off: 5 s, 10 s, … up to two minutes, so an offline camera doesn't spam Google.
            delay(minOf(120_000L, 5_000L * failures.coerceAtLeast(1)))
            _status.value = Status.Connecting
        }
    }

    /**
     * Shows this stream in [sink], now and after every reconnect. [wantsAudio]: play its sound while
     * attached (attaching the same sink again just changes that).
     */
    fun attach(sink: VideoSink, wantsAudio: Boolean) {
        synchronized(tracks) {
            val isNew = sinks.put(sink, wantsAudio) == null
            // Only a track of the live connection: teardown() lets go of it under this lock first.
            if (isNew) video?.let { v -> runCatching { v.addSink(sink) }.onFailure { Log.w("CameraStream", "attach", it) } }
        }
        updateAudio()
    }

    /**
     * Stops showing this stream in [sink]. Once this returns no frame reaches it any more (WebRTC
     * waits for one being delivered), so the view's renderer can be released straight after.
     */
    fun detach(sink: VideoSink) {
        synchronized(tracks) {
            if (sinks.remove(sink) == null) return
            video?.let { v -> runCatching { v.removeSink(sink) }.onFailure { Log.w("CameraStream", "detach", it) } }
        }
        updateAudio()
    }

    /** Sound plays while any attached view wants it. */
    private fun updateAudio() {
        val want = synchronized(tracks) { sinks.containsValue(true) }
        if (want != audioOn) setAudio(want)
    }

    /** Synchronized with [teardown], so it never touches a track that's being disposed. */
    @Synchronized
    private fun setAudio(on: Boolean) {
        audioOn = on
        // Called outside [tracks]: switching the sound waits on the signaling thread, where onTrack takes that lock.
        val track = synchronized(tracks) { audio }
        runCatching { track?.setEnabled(on) }
    }

    /**
     * Stops the stream (only [CameraStreams] does, once no view shows it); [then] runs (on a
     * background thread) once the connection is fully torn down and Google told to stop.
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
        // Marks this connection's callbacks, so late ones from it change nothing after teardown.
        val token = Any()

        val conn = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (synchronized(tracks) { live !== token }) return
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
                    // Every view attached so far gets this connection's picture (and later ones in attach()).
                    is VideoTrack -> synchronized(tracks) {
                        if (live !== token) return@synchronized
                        video = track
                        sinks.keys.forEach { s -> runCatching { track.addSink(s) }.onFailure { Log.w("CameraStream", "addSink", it) } }
                    }
                    is AudioTrack -> {
                        val mine = synchronized(tracks) { (live === token).also { if (it) audio = track } }
                        // Already on the signaling thread, and teardown can't dispose it until this returns.
                        if (mine) runCatching { track.setEnabled(audioOn) }
                    }
                    else -> Unit
                }
            }
        }) ?: throw IllegalStateException("WebRTC isn't available on this TV")
        pc = conn
        synchronized(tracks) { live = token }

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

    /**
     * Synchronized: a double dispose crashes natively, and [setAudio] mustn't touch a track while
     * it's disposed. Only the stream's own coroutine calls this.
     */
    @Synchronized
    private fun teardown() {
        dropped = null
        // First the views stop being fed, under the lock attach(), detach() and onTrack use: from here
        // on nothing touches this connection's tracks (they're disposed below), and its late onTrack
        // changes nothing. The connection is closed outside the lock: closing waits on the signaling
        // thread, where onTrack may be waiting for it.
        synchronized(tracks) {
            live = null
            video?.let { v -> sinks.keys.forEach { s -> runCatching { v.removeSink(s) } } }
            video = null
            audio = null
        }
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

    /**
     * Shared by every camera on this TV: new streams start one at a time a couple of seconds
     * apart, and when Google says "too many requests" all of them wait it out together (1 minute,
     * doubling up to 8) instead of each retrying on its own.
     */
    private object Gate {
        private val lock = Mutex()
        @Volatile private var nextStart = 0L
        @Volatile private var cooldownUntil = 0L
        @Volatile private var strikes = 0

        suspend fun awaitTurn() = lock.withLock {
            val now = System.currentTimeMillis()
            val wait = maxOf(nextStart, cooldownUntil) - now
            if (wait > 0) delay(wait)
            nextStart = System.currentTimeMillis() + 2_000L
            // A stream got through after a cooldown: start forgiving.
            if (cooldownUntil in 1..now) strikes = maxOf(0, strikes - 1)
        }

        fun limited() {
            strikes = minOf(strikes + 1, 4)
            cooldownUntil = System.currentTimeMillis() + 60_000L * (1L shl (strikes - 1))
        }
    }
}
