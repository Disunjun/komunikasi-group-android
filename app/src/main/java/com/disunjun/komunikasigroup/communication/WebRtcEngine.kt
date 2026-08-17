package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.CandidateWire
import com.disunjun.komunikasigroup.domain.IceServer

/** Which side produced the remote SDP fed to [WebRtcEngine.setRemoteDescription]. */
enum class RemoteDescriptionType { OFFER, ANSWER }

/**
 * Engine-side lifecycle of the local RTCPeerConnection for one media session.
 *
 * The engine deals only in SDP strings and [CandidateWire] — no Socket.IO and no
 * media signaling payloads cross this boundary. Signal strength / connection
 * state is deliberately reported as a coarse domain state, not raw RTC strings.
 */
enum class RtcPeerConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** Snapshots an engine-level negotiated result so the manager can relay it. */
sealed class RtcEvent {
    object SignalingReady : RtcEvent()
    data class OfferReady(val sdp: String) : RtcEvent()
    data class AnswerReady(val sdp: String) : RtcEvent()
    data class IceCandidateReady(val candidate: CandidateWire) : RtcEvent()
    data class ConnectionStateChanged(val state: RtcPeerConnectionState) : RtcEvent()
    data class Failure(val message: String) : RtcEvent()
}

/** Callback bridge from the engine to the signaling manager. */
interface RtcEventListener {
    fun onRtcEvent(event: RtcEvent)
}

/**
 * Local WebRTC engine. Implementations own the native RTCPeerConnection and
 * translate native callbacks into [RtcEvent]s. No signaling is described here.
 */
interface WebRtcEngine {
    /** Initialize the engine; idempotent. Returns success once ready to create a connection. */
    fun initialize(): Result<Unit>

    /**
     * Create a fresh RTCPeerConnection for [remotePeerId] bound to [listener].
     * [iceServers] are the already-verified TURN/STUN servers (never logged).
     */
    fun createPeerConnection(
        sessionId: String,
        remotePeerId: String,
        listener: RtcEventListener,
        iceServers: List<IceServer>
    ): Result<Unit>

    /** Ask the engine to produce an SDP offer (caller side). */
    fun createOffer(): Result<Unit>

    /** Ask the engine to produce an SDP answer to the given remote offer (callee side). */
    fun createAnswer(remoteSdp: String): Result<Unit>

    /**
     * Feed the remote SDP into the peer connection. [type] must be OFFER when the
     * remote is the caller (remote offer) and ANSWER when the remote is the callee.
     */
    fun setRemoteDescription(sdp: String, type: RemoteDescriptionType): Result<Unit>

    /** Feed a remote ICE candidate into the peer connection. */
    fun addIceCandidate(candidate: CandidateWire): Result<Unit>

    /** Close and release native resources; idempotent. */
    fun close(): Result<Unit>
}