package com.woodruff.tvexplorer.nest

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.woodruff.tvexplorer.util.NetworkWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import org.webrtc.VideoFrame
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
 *
 * A picture is live only while its frames come ([StreamHealth]): a connection that's up but sends
 * nothing (a camera asleep, Google ending the media, the Wi-Fi gone for a moment) isn't shown as live.
 * A few seconds without a frame and the views hold the last picture and say so; longer, or when
 * WebRTC gives the connection up, it's made again, waiting longer after each failure until frames
 * flow again for a while. Google's live streams end after about five minutes, so this extends the
 * stream every four.
 */
class CameraStream(private val context: Context, private val camera: Camera) {
    /** What the stream's views show. */
    sealed interface Status {
        /** Starting: no picture yet. */
        data object Connecting : Status

        /** Frames are arriving: the picture is live. */
        data object Live : Status

        /** Still connected, but no frame for a few seconds: the views hold the last picture. [since]: its last frame (elapsedRealtime). */
        data class Stalled(val since: Long) : Status

        /** The connection dropped or couldn't be made, and is being made again. [since]: when the trouble began (elapsedRealtime). */
        data class Reconnecting(val since: Long) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status
    @Volatile private var pc: PeerConnection? = null
    /** Google requires a data channel. PeerConnection.dispose() doesn't free it (nor does a finalizer): it's kept to dispose with the connection. */
    @Volatile private var channel: DataChannel? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var audioOn = false
    /** Renewing Google's session, when that's under way (beside the frame watch, so a renewal that hangs never hides frames stopping). */
    @Volatile private var renewal: Deferred<String>? = null
    @Volatile private var job: Job? = null
    @Volatile private var onClosed: (() -> Unit)? = null

    // The views' pictures (each with whether it wants sound) and the current connection's tracks,
    // guarded by [tracks] alone, never this object's monitor: onTrack runs on WebRTC's signaling
    // thread and takes this lock, while teardown() and syncAudio() hold the monitor and wait on that
    // same thread (closing the connection, switching the sound), so one lock for both would deadlock.
    private val tracks = Any()
    private val sinks = LinkedHashMap<VideoSink, Boolean>()
    /** The connection whose tracks these are; onTrack from an older one is ignored. */
    private var live: Any? = null
    private var video: VideoTrack? = null
    private var audio: AudioTrack? = null
    /** When the connection's frames come: a sink of its own on the picture, besides the views'. */
    private var frames: FrameClock? = null

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
        val health = StreamHealth()
        while (true) {
            val mark = NetworkWatch.comebacks.value
            var stopOld: String? = null
            try {
                Gate.awaitTurn()
                keepUp(connect(), health, mark)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${camera.label}: ${e.message}")
                val cause = (e as? RenewalFailed)?.cause ?: e
                if (cause is RateLimited) Gate.limited()
                // Google turned down a renewal (not "too many requests", not the network): its old
                // session is told to stop before a new one is asked for. Nothing else is: a dropped
                // or stuck connection's session ends by itself in a few minutes.
                if (e is RenewalFailed && stopsOldSession(cause)) stopOld = e.session
                // Its session is let go either way: closing the stream later doesn't stop it again.
                sessionId = null
                _status.value = health.failed(clock())
            }
            teardown()
            val stopping = stopOld?.let { id -> scope.launch { NestApi.stopStream(context, camera, id) } }
            // Back off: 5 s more after each failure in a row, up to two minutes, so a camera that's
            // off doesn't spam Google; but at once when the TV's network comes back (woken from
            // standby), not minutes later.
            NetworkWatch.awaitComeback(mark, health.pauseMs())
            // The old session's stop gets a moment more, never long: it's only tidiness.
            if (stopping != null) withTimeoutOrNull(STOP_WAIT_MS) { stopping.join() }
            _status.value = health.connecting()
        }
    }

    /**
     * Keeps connection [c] going until it should be dropped (it returns only by throwing): judges its
     * frames twice a second ([StreamHealth]), and extends Google's session every four minutes.
     */
    private suspend fun keepUp(c: Connection, health: StreamHealth, mark: Int) {
        var renewAt = clock() + EXTEND_EVERY_MS
        while (true) {
            if (withTimeoutOrNull(TICK_MS) { c.dropped.await() } != null) throw ConnectionLost()
            val now = clock()
            val first = c.frames.first
            val status = health.judge(now, c.answeredAt, first, c.frames.last) ?: throw NoPicture(first > 0)
            // The network came back while the picture was stuck: the old connection's way out has
            // likely gone with it, so it's made again now rather than in 15 s.
            if (status is Status.Stalled && NetworkWatch.comebacks.value != mark) throw NetworkChanged()
            _status.value = status

            val r = renewal
            if (r == null) {
                if (now >= renewAt) {
                    val id = sessionId ?: throw IllegalStateException("No live session to extend")
                    renewal = scope.async { NestApi.extendStream(context, camera, id) }
                }
            } else if (r.isCompleted) {
                renewal = null
                // Already finished, so this never waits (and can't be this coroutine's cancellation).
                val renewed = runCatching { r.await() }
                sessionId = renewed.getOrElse { e -> throw RenewalFailed(sessionId, e as? Exception ?: IllegalStateException(e)) }
                renewAt = clock() + EXTEND_EVERY_MS
            }
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
            if (isNew) video?.let { v -> runCatching { v.addSink(sink) }.onFailure { Log.w(TAG, "attach", it) } }
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
            video?.let { v -> runCatching { v.removeSink(sink) }.onFailure { Log.w(TAG, "detach", it) } }
        }
        updateAudio()
    }

    /**
     * Sound plays while any attached view wants it. Switched on the stream's own background thread,
     * not the caller's (the main thread, moving between cameras): switching waits on WebRTC.
     */
    private fun updateAudio() {
        scope.launch { syncAudio() }
    }

    /**
     * Synchronized with [teardown], so it never touches a track that's being disposed. Reads what's
     * wanted now, so switches that run out of order still end as the views want.
     */
    @Synchronized
    private fun syncAudio() {
        val want = synchronized(tracks) { sinks.containsValue(true) }
        if (want == audioOn) return
        audioOn = want
        // Called outside [tracks]: switching the sound waits on the signaling thread, where onTrack takes that lock.
        val track = synchronized(tracks) { audio }
        runCatching { track?.setEnabled(want) }
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

    /** A connection made: its frames, WebRTC giving it up, and when Google's answer was applied. */
    private class Connection(val frames: FrameClock, val dropped: CompletableDeferred<Unit>, val answeredAt: Long)

    private suspend fun connect(): Connection {
        val factory = Rtc.factory(context)
        val gathered = CompletableDeferred<Unit>()
        val drop = CompletableDeferred<Unit>()
        val frameClock = FrameClock()
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        // Marks this connection's callbacks, so late ones from it change nothing after teardown.
        val token = Any()
        fun mine() = synchronized(tracks) { live === token }

        val conn = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (!mine()) return
                when (state) {
                    // DISCONNECTED often recovers by itself (the frames say if it doesn't); FAILED doesn't.
                    PeerConnection.IceConnectionState.FAILED -> drop.complete(Unit)
                    else -> Unit
                }
            }
            // The whole connection's state (the network path and its encryption): FAILED gives it up too.
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (!mine()) return
                if (newState == PeerConnection.PeerConnectionState.FAILED) drop.complete(Unit)
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
                    is VideoTrack -> synchronized(tracks) {
                        if (live !== token) return@synchronized
                        video = track
                        // When its frames come, then every view attached so far (and later ones in attach()).
                        frames?.let { f -> runCatching { track.addSink(f) }.onFailure { Log.w(TAG, "frames", it) } }
                        sinks.keys.forEach { s -> runCatching { track.addSink(s) }.onFailure { Log.w(TAG, "addSink", it) } }
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
        synchronized(tracks) {
            live = token
            frames = frameClock
        }

        // Google requires audio, then video, then a data channel in the offer, in that order.
        val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
        conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        channel = conn.createDataChannel("dataSendChannel", DataChannel.Init())

        val offer = sdp { obs -> conn.createOffer(obs, MediaConstraints()) }
        setDescription { obs -> conn.setLocalDescription(obs, offer) }
        // Include our network candidates in the offer (Google doesn't take trickled ones).
        withTimeoutOrNull(3_000) { gathered.await() }
        val local = conn.localDescription?.description ?: offer.description

        val session = NestApi.startStream(context, camera, local)
        sessionId = session.mediaSessionId
        setDescription { obs -> conn.setRemoteDescription(obs, SessionDescription(SessionDescription.Type.ANSWER, session.answerSdp)) }
        return Connection(frameClock, drop, clock())
    }

    /**
     * Synchronized: a double dispose crashes natively, and [syncAudio] mustn't touch a track while
     * it's disposed. Only the stream's own coroutine calls this.
     */
    @Synchronized
    private fun teardown() {
        // A renewal still under way is for this connection: whatever it brings back isn't needed.
        renewal?.cancel()
        renewal = null
        // First the views stop being fed, under the lock attach(), detach() and onTrack use: from here
        // on nothing touches this connection's tracks (they're disposed below), and its late onTrack
        // changes nothing. The connection is closed outside the lock: closing waits on the signaling
        // thread, where onTrack may be waiting for it.
        synchronized(tracks) {
            live = null
            video?.let { v ->
                sinks.keys.forEach { s -> runCatching { v.removeSink(s) } }
                frames?.let { f -> runCatching { v.removeSink(f) } }
            }
            video = null
            audio = null
            frames = null
        }
        val dc = channel
        channel = null
        val p = pc ?: return
        pc = null
        runCatching { p.close() }
        // After the connection is closed and before it's disposed (dispose() frees its streams,
        // senders, receivers and transceivers, never its data channels): one leaked native object per
        // connection otherwise, about one a minute on a TV that rotates its tiles, for days.
        runCatching { dc?.dispose() }
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
        private const val TAG = "CameraStream"
        private const val EXTEND_EVERY_MS = 4 * 60_000L
        /** How often the frames are judged. */
        private const val TICK_MS = 500L
        /** How long the next try waits, at most, for an old session's stop to reach Google. */
        private const val STOP_WAIT_MS = 3_000L

        private fun clock() = SystemClock.elapsedRealtime()
    }

    /** WebRTC gave the connection up. */
    private class ConnectionLost : IllegalStateException("Connection lost")

    /** No picture for too long ([hadOne]: it stopped, rather than never came): dropped, to be made again. */
    private class NoPicture(val hadOne: Boolean) : IllegalStateException(if (hadOne) "The picture stopped" else "No picture came")

    /** The TV's network came back while the picture was stuck. */
    private class NetworkChanged : IllegalStateException("The network came back")

    /** Extending Google's session [session] failed with [cause]. */
    private class RenewalFailed(val session: String?, override val cause: Exception) : IllegalStateException("Couldn't extend the stream: ${cause.message}", cause)

    /**
     * When a connection's frames arrive (on WebRTC's decoder thread): its first and its latest, by the
     * uptime clock. A sink on the picture that draws nothing, so the stream knows its frames are coming
     * whatever its views do with them (a tile skips most).
     */
    private class FrameClock : VideoSink {
        @Volatile var first = 0L
            private set
        @Volatile var last = 0L
            private set

        override fun onFrame(frame: VideoFrame) {
            val now = clock()
            if (first == 0L) first = now
            last = now
        }
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
