package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.CandidateWire
import com.disunjun.komunikasigroup.domain.CommunicationError
import com.disunjun.komunikasigroup.domain.IceServer
import com.disunjun.komunikasigroup.domain.MediaErrorCode
import com.disunjun.komunikasigroup.domain.MediaSession
import com.disunjun.komunikasigroup.domain.MediaSessionState
import com.disunjun.komunikasigroup.domain.MediaSignalCodec
import com.disunjun.komunikasigroup.domain.MediaSignalParseResult
import com.disunjun.komunikasigroup.domain.TurnCredentialProvider
import java.util.UUID

/** Surface used by MediaSignalingManager to publish session/peer state. */
interface MediaSessionEventListener {
    fun onMediaSessionState(state: MediaSession)
    fun onMediaError(code: MediaErrorCode?, message: String)
}

/**
 * Transport boundary for the v3 media relay. The manager drives this with pure
 * Map payloads; implementations own the Socket.IO lifetime and convert frames.
 */
interface MediaSignalTransport {
    fun isConnected(): Boolean
    fun joinChannel(channelId: String)
    fun emit(event: String, payload: Map<String, Any?>)
    fun disconnect()
}

/** Notifies the manager of inbound relayed frames and connectivity changes. */
fun interface MediaSignalTransportListener {
    fun onMediaFrame(event: String, payload: Map<String, Any?>)
}

/**
 * Mapping layer between the local [WebRtcEngine] and the v3 media relay.
 *
 * Responsibilities:
 *  - drive a single [MediaSession] through its state machine
 *  - translate engine events ([RtcEvent]) into relay frames (media:offer/answer/ice)
 *  - translate inbound relay frames into engine calls
 *  - route media:error --> domain error state (the v3 relay always echoes
 *    `sessionId`, and `toPeerId` in outbound frames is the remote v3 sessionId)
 *
 * Identity rule: [localPeerId] is a stable android peerId; the remote routing
 * identity used in outbound frames is the target's v3 sessionId. The two are
 * intentionally decoupled. No token/SDP/ICE data is logged anywhere in this
 * class.
 */
class MediaSignalingManager(
    private val engine: WebRtcEngine,
    private val transport: MediaSignalTransport,
    private val turnCredentialProvider: TurnCredentialProvider?,
    private val localPeerId: String,
    private val group: String,
    private val channel: String
) : RtcEventListener, MediaSignalTransportListener {

    private var listener: MediaSessionEventListener? = null
    private var session: MediaSession? = null
    private var outgoingSessionId: String? = null
    private var answerAfterRemote = false

    fun setListener(listener: MediaSessionEventListener?) {
        this.listener = listener
    }

    /** Caller side: start a new outgoing WebRTC session towards [remoteSessionId]. */
    fun initiateCall(remoteSessionId: String): Result<Unit> {
        if (remoteSessionId.isBlank()) {
            return Result.failure(CommunicationError.Room("Target sesi tidak valid."))
        }
        val sessionId = UUID.randomUUID().toString()
        val mediaSession = MediaSession(
            sessionId = sessionId,
            localPeerId = localPeerId,
            remotePeerId = remoteSessionId,
            group = group,
            channel = channel,
            state = MediaSessionState.SIGNALING
        )
        session = mediaSession
        outgoingSessionId = sessionId
        listener?.onMediaSessionState(mediaSession.copyState(MediaSessionState.NEW))

        return runCatching {
            val iceServers = fetchIceServers()
            checkReady(mediaSession)
            engine.createPeerConnection(sessionId, remoteSessionId, this, iceServers)
                .getOrElse { throw it }
            listener?.onMediaSessionState(mediaSession.copyState(MediaSessionState.SIGNALING))
            engine.createOffer().getOrElse { throw it }
        }.onFailure {
            transition(mediaSession, MediaSessionState.FAILED)
            listener?.onMediaError(MediaErrorCode.INVALID_PAYLOAD, it.message ?: "Gagal memulai panggilan.")
        }
    }

    /** Callee side: handle an inbound offer and produce an answer. */
    private fun onInboundOffer(fromPeerId: String, sessionId: String, sdp: String): Boolean {
        val active = session
        if (active != null && active.sessionId == sessionId && active.remotePeerId == fromPeerId) {
            // Re-offer on an existing session — not supported in V1 PoC.
            return false
        }
        if (active != null && active.state != MediaSessionState.CLOSED &&
            active.state != MediaSessionState.FAILED &&
            active.state != MediaSessionState.DISCONNECTED
        ) {
            // Busy with another session; reject rather than corrupt state.
            return false
        }

        val newSession = MediaSession(
            sessionId = sessionId,
            localPeerId = localPeerId,
            remotePeerId = fromPeerId,
            group = group,
            channel = channel,
            state = MediaSessionState.SIGNALING
        )
        session = newSession
        outgoingSessionId = null
        listener?.onMediaSessionState(newSession.copyState(MediaSessionState.NEW))

        return runCatching {
            val iceServers = fetchIceServers()
            checkReady(newSession)
            engine.createPeerConnection(sessionId, fromPeerId, this, iceServers)
                .getOrElse { throw it }
            answerAfterRemote = true
            engine.setRemoteDescription(sdp, RemoteDescriptionType.OFFER).getOrElse { throw it }
            listener?.onMediaSessionState(newSession.copyState(MediaSessionState.SIGNALING))
        }.onFailure {
            transition(newSession, MediaSessionState.FAILED)
            listener?.onMediaError(MediaErrorCode.INVALID_PAYLOAD, it.message ?: "Gagal menjawab panggilan.")
        }.isSuccess
    }

    /** Handle an inbound answer bound to an outgoing session we created. */
    private fun onInboundAnswer(fromPeerId: String, sessionId: String, sdp: String) {
        val mediaSession = session ?: return
        if (mediaSession.sessionId != sessionId || mediaSession.remotePeerId != fromPeerId) return
        runCatching {
            engine.setRemoteDescription(sdp, RemoteDescriptionType.ANSWER).getOrElse { throw it }
        }.onFailure {
            transition(mediaSession, MediaSessionState.FAILED)
            listener?.onMediaError(MediaErrorCode.INVALID_PAYLOAD, it.message ?: "Gagal memproses answer.")
        }
    }

    private fun onInboundIce(fromPeerId: String, sessionId: String, candidate: CandidateWire) {
        val mediaSession = session ?: return
        if (mediaSession.sessionId != sessionId || mediaSession.remotePeerId != fromPeerId) return
        runCatching { engine.addIceCandidate(candidate).getOrElse { throw it } }
    }

    private fun onInboundLeave(sessionId: String) {
        val mediaSession = session ?: return
        if (mediaSession.sessionId != sessionId) return
        runCatching { engine.close() }
        transition(mediaSession, MediaSessionState.CLOSED)
        listener?.onMediaError(null, "Panggilan diakhiri rekan.")
    }

    /** Terminate the current session and broadcast media:leave to the relay room. */
    fun leave(): Result<Unit> {
        val mediaSession = session ?: return Result.failure(CommunicationError.Room("Tidak ada sesi aktif."))
        if (mediaSession.sessionId.isNotBlank()) {
            runCatching { transport.emit("media:leave", MediaSignalCodec.buildLeave(mediaSession.sessionId)) }
        }
        runCatching { engine.close() }
        transition(mediaSession, MediaSessionState.CLOSED)
        return Result.success(Unit)
    }

    fun shutdown() {
        val mediaSession = session
        if (mediaSession != null && mediaSession.sessionId.isNotBlank()) {
            runCatching { transport.emit("media:leave", MediaSignalCodec.buildLeave(mediaSession.sessionId)) }
        }
        runCatching { engine.close() }
        session = null
        listener = null
    }

    // ---- engine -> relay ----

    override fun onRtcEvent(event: RtcEvent) {
        val mediaSession = session ?: return
        val target = mediaSession.remotePeerId

        when (event) {
            is RtcEvent.OfferReady -> {
                outgoingSessionId = mediaSession.sessionId
                val to = target ?: return
                if (mediaSession.sessionId.isBlank() || to.isBlank()) return
                transport.emit("media:offer", MediaSignalCodec.buildOffer(mediaSession.sessionId, to, event.sdp))
            }
            is RtcEvent.AnswerReady -> {
                val to = target ?: return
                if (mediaSession.sessionId.isBlank() || to.isBlank()) return
                transport.emit("media:answer", MediaSignalCodec.buildAnswer(mediaSession.sessionId, to, event.sdp))
            }
            is RtcEvent.IceCandidateReady -> {
                val to = target ?: return
                if (mediaSession.sessionId.isBlank() || to.isBlank()) return
                transport.emit(
                    "media:ice-candidate",
                    MediaSignalCodec.buildIce(mediaSession.sessionId, to, event.candidate)
                )
            }
            is RtcEvent.ConnectionStateChanged -> when (event.state) {
                RtcPeerConnectionState.CONNECTED -> transitionConnect(mediaSession, MediaSessionState.CONNECTED)
                RtcPeerConnectionState.FAILED -> transition(mediaSession, MediaSessionState.FAILED)
                RtcPeerConnectionState.DISCONNECTED,
                RtcPeerConnectionState.CLOSED -> transition(mediaSession, MediaSessionState.DISCONNECTED)
                else -> Unit
            }
            RtcEvent.SignalingReady -> {
                if (answerAfterRemote) {
                    answerAfterRemote = false
                    val media = session ?: return
                    runCatching { engine.createAnswer("").getOrElse { throw it } }.onFailure {
                        transition(media, MediaSessionState.FAILED)
                        listener?.onMediaError(MediaErrorCode.INVALID_PAYLOAD, it.message ?: "Gagal membuat answer.")
                    }
                }
            }
            is RtcEvent.Failure -> {
                transition(mediaSession, MediaSessionState.FAILED)
                listener?.onMediaError(null, event.message)
            }
        }
    }

    // ---- relay -> engine ----

    override fun onMediaFrame(event: String, payload: Map<String, Any?>) {
        val parsed = MediaSignalCodec.parseInbound(payload) ?: return
        when (parsed) {
            is MediaSignalParseResult.Offer -> onInboundOffer(parsed.fromPeerId, parsed.sessionId, parsed.sdp)
            is MediaSignalParseResult.Answer -> onInboundAnswer(parsed.fromPeerId, parsed.sessionId, parsed.sdp)
            is MediaSignalParseResult.IceCandidate -> onInboundIce(parsed.fromPeerId, parsed.sessionId, parsed.candidate)
            is MediaSignalParseResult.Leave -> onInboundLeave(parsed.sessionId)
            is MediaSignalParseResult.Error -> onMediaErrorFrame(parsed)
        }
    }

    private fun onMediaErrorFrame(error: MediaSignalParseResult.Error) {
        val mediaSession = session ?: run {
            listener?.onMediaError(error.code, error.message)
            return
        }
        if (error.sessionId == null || error.sessionId == mediaSession.sessionId) {
            transition(mediaSession, MediaSessionState.FAILED)
            listener?.onMediaError(error.code, error.message)
        }
    }

    // ---- helpers ----

    private fun checkReady(mediaSession: MediaSession): Boolean {
        if (mediaSession.group.isBlank() || mediaSession.channel.isBlank()) {
            throw CommunicationError.Room("Group/channel belum ditentukan.")
        }
        return true
    }

    private fun fetchIceServers(): List<IceServer> {
        val provider = turnCredentialProvider ?: return emptyList()
        val credentials = provider.getTurnCredentials().getOrElse { return emptyList() }
        return credentials.iceServers
    }

    private fun transition(mediaSession: MediaSession, state: MediaSessionState) {
        listener?.onMediaSessionState(mediaSession.copyState(state))
    }

    private fun transitionConnect(mediaSession: MediaSession, state: MediaSessionState) {
        if (session?.sessionId == mediaSession.sessionId) {
            listener?.onMediaSessionState(mediaSession.copyState(state))
        }
    }
}