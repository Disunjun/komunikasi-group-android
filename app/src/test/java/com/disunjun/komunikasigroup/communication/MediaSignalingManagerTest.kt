package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.CandidateWire
import com.disunjun.komunikasigroup.domain.IceServer
import com.disunjun.komunikasigroup.domain.MediaErrorCode
import com.disunjun.komunikasigroup.domain.MediaSession
import com.disunjun.komunikasigroup.domain.MediaSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory WebRtcEngine implementation for manager behavior tests. */
class FakeEngine : WebRtcEngine {
    var initialized = false
    var created = 0
    var offered = false
    var answered = false
    var lastRemoteSdp: String? = null
    var lastRemoteType: RemoteDescriptionType? = null
    var lastCandidate: CandidateWire? = null
    var listener: RtcEventListener? = null
    var closed = false

    override fun initialize(): Result<Unit> {
        initialized = true
        return Result.success(Unit)
    }

    override fun createPeerConnection(
        sessionId: String,
        remotePeerId: String,
        listener: RtcEventListener,
        iceServers: List<IceServer>
    ): Result<Unit> {
        initialize().getOrThrow()
        created += 1
        this.listener = listener
        return Result.success(Unit)
    }

    override fun createOffer(): Result<Unit> {
        offered = true
        listener?.onRtcEvent(RtcEvent.OfferReady("v=0 offer (local)"))
        return Result.success(Unit)
    }

    override fun createAnswer(remoteSdp: String): Result<Unit> {
        answered = true
        listener?.onRtcEvent(RtcEvent.AnswerReady("v=0 answer (local)"))
        return Result.success(Unit)
    }

    override fun setRemoteDescription(sdp: String, type: RemoteDescriptionType): Result<Unit> {
        lastRemoteSdp = sdp
        lastRemoteType = type
        return Result.success(Unit)
    }

    override fun addIceCandidate(candidate: CandidateWire): Result<Unit> {
        lastCandidate = candidate
        return Result.success(Unit)
    }

    override fun close(): Result<Unit> {
        closed = true
        return Result.success(Unit)
    }

    fun emit(event: RtcEvent) = listener?.onRtcEvent(event)
}

/** In-memory transport capturing emitted frames. */
class FakeTransport : MediaSignalTransport {
    val emitted = mutableListOf<Pair<String, Map<String, Any?>>>()
    var connected = false
    var joinedChannel: String? = null

    override fun isConnected(): Boolean = connected

    override fun joinChannel(channelId: String) {
        joinedChannel = channelId
    }

    override fun emit(event: String, payload: Map<String, Any?>) {
        emitted.add(event to payload)
    }

    override fun disconnect() {
        connected = false
    }

    fun lastFrameOf(event: String): Map<String, Any?>? =
        emitted.lastOrNull { it.first == event }?.second

    fun frameCount(event: String): Int = emitted.count { it.first == event }
}

class MediaSignalingManagerTest {

    private val engine = FakeEngine()
    private val transport = FakeTransport()
    private val states = mutableListOf<MediaSession>()
    private val errors = mutableListOf<Pair<MediaErrorCode?, String>>()

    private fun manager(): MediaSignalingManager {
        val m = MediaSignalingManager(
            engine = engine,
            transport = transport,
            turnCredentialProvider = null,
            localPeerId = "android-test-peer",
            group = "div-a",
            channel = "ch-1"
        )
        m.setListener(object : MediaSessionEventListener {
            override fun onMediaSessionState(state: MediaSession) {
                states.add(state)
            }

            override fun onMediaError(code: MediaErrorCode?, message: String) {
                errors.add(code to message)
            }
        })
        return m
    }

    @Test
    fun `initiateCall creates engine and emits offer frame with consistent sessionId`() {
        val m = manager()
        val result = m.initiateCall("target-session-b")

        assertTrue(result.isSuccess)
        assertTrue(engine.initialized)
        assertEquals(1, engine.created)
        assertTrue(engine.offered)

        val offer = transport.lastFrameOf("media:offer")
        assertTrue(offer != null)
        assertEquals("target-session-b", offer!!["toPeerId"])
        val sessionId = offer["sessionId"] as String
        assertTrue(sessionId.isNotBlank())

        assertEquals(states.firstOrNull()?.sessionId, sessionId)
    }

    @Test
    fun `inbound offer triggers answer using the offer sessionId`() {
        val m = manager()
        val offerSessionId = "remote-offer-session-1"

        m.onMediaFrame(
            "media:offer",
            mapOf(
                "type" to "offer",
                "sessionId" to offerSessionId,
                "fromPeerId" to "target-session-b",
                "sdp" to "v=0 offer"
            )
        )

        assertEquals(1, engine.created)
        assertEquals(RemoteDescriptionType.OFFER, engine.lastRemoteType)
        assertEquals("v=0 offer", engine.lastRemoteSdp)
        // createAnswer happens after signaling-ready; simulate the SDP sequence
        engine.emit(RtcEvent.SignalingReady)
        assertTrue(engine.answered)

        val answer = transport.lastFrameOf("media:answer")
        assertTrue(answer != null)
        assertEquals(offerSessionId, answer!!["sessionId"])
        assertEquals("target-session-b", answer["toPeerId"])
    }

    @Test
    fun `engine candidate is relayed with embedded wire candidate`() {
        val m = manager()
        m.initiateCall("target-session-b")
        val sessionId = transport.lastFrameOf("media:offer")!!["sessionId"] as String

        val candidate = CandidateWire("0", 0, "candidate:host")
        engine.emit(RtcEvent.IceCandidateReady(candidate))

        val ice = transport.lastFrameOf("media:ice-candidate")
        assertTrue(ice != null)
        assertEquals(sessionId, ice!!["sessionId"])
        assertEquals("target-session-b", ice["toPeerId"])
        val wire = ice["candidate"] as String
        assertTrue(wire.startsWith("{"))
    }

    @Test
    fun `inbound candidate for the active session is applied to engine`() {
        val m = manager()
        m.initiateCall("target-session-b")
        val sessionId = transport.lastFrameOf("media:offer")!!["sessionId"] as String

        m.onMediaFrame(
            "media:ice-candidate",
            mapOf(
                "sessionId" to sessionId,
                "fromPeerId" to "target-session-b",
                "candidate" to "{\"sdpMid\":\"0\",\"sdpMLineIndex\":0,\"sdp\":\"candidate:1\"}"
            )
        )

        assertEquals("0", engine.lastCandidate?.sdpMid)
        assertEquals("candidate:1", engine.lastCandidate?.candidate)
    }

    @Test
    fun `inbound leave for the active session closes engine and transitions CLOSED`() {
        val m = manager()
        m.initiateCall("target-session-b")
        val sessionId = transport.lastFrameOf("media:offer")!!["sessionId"] as String

        m.onMediaFrame("media:leave", mapOf("sessionId" to sessionId, "peerId" to "target-session-b"))

        assertTrue(engine.closed)
        assertEquals(MediaSessionState.CLOSED, states.last().state)
    }

    @Test
    fun `local leave emits leave frame and closes engine`() {
        val m = manager()
        m.initiateCall("target-session-b")
        val sessionId = transport.lastFrameOf("media:offer")!!["sessionId"] as String

        val result = m.leave()
        assertTrue(result.isSuccess)

        val leave = transport.lastFrameOf("media:leave")
        assertTrue(leave != null)
        assertEquals(sessionId, leave!!["sessionId"])
        assertTrue(engine.closed)
        assertEquals(MediaSessionState.CLOSED, states.last().state)
    }

    @Test
    fun `relay error maps to FAILED state and surfaces the error code`() {
        val m = manager()
        m.initiateCall("target-session-b")
        val sessionId = transport.lastFrameOf("media:offer")!!["sessionId"] as String

        m.onMediaFrame(
            "media:error",
            mapOf("code" to "PEER_ID_MISMATCH", "message" to "nope", "sessionId" to sessionId)
        )

        assertEquals(MediaSessionState.FAILED, states.last().state)
        assertTrue(errors.last().first == MediaErrorCode.PEER_ID_MISMATCH)
    }

    @Test
    fun `session mismatch - inbound answer for unknown session is ignored`() {
        val m = manager()
        m.initiateCall("target-session-b")

        m.onMediaFrame(
            "media:answer",
            mapOf("sessionId" to "UNKNOWN", "fromPeerId" to "target-session-b", "sdp" to "v=0 answer")
        )

        assertNull(engine.lastRemoteSdp)
        assertEquals(0, transport.frameCount("media:answer"))
    }

    @Test
    fun `peer mismatch - inbound candidate from wrong peer is ignored`() {
        val m = manager()
        m.initiateCall("target-session-b")
        val sessionId = transport.lastFrameOf("media:offer")!!["sessionId"] as String

        m.onMediaFrame(
            "media:ice-candidate",
            mapOf(
                "sessionId" to sessionId,
                "fromPeerId" to "IMPERSONATOR",
                "candidate" to "{\"sdpMid\":\"0\",\"sdpMLineIndex\":0,\"sdp\":\"candidate:1\"}"
            )
        )

        assertNull(engine.lastCandidate)
    }

    @Test
    fun `leave with non-matching session does not close the active session`() {
        val m = manager()
        m.initiateCall("target-session-b")

        m.onMediaFrame("media:leave", mapOf("sessionId" to "OTHER", "peerId" to "target-session-b"))

        assertTrue(!engine.closed)
        assertTrue(states.none { it.state == MediaSessionState.CLOSED })
    }

    @Test
    fun `echo of local offer never answers own session`() {
        val m = manager()
        val result = m.initiateCall("target-session-b")
        assertTrue(result.isSuccess)
        val sessionId = transport.lastFrameOf("media:offer")!!["sessionId"] as String
        val createdAfterOffer = engine.created

        // Echo the offer back (server routing fault or capture loop).
        m.onMediaFrame(
            "media:offer",
            mapOf(
                "type" to "offer",
                "sessionId" to sessionId,
                "fromPeerId" to "target-session-b",
                "sdp" to "v=0 offer"
            )
        )

        assertEquals(createdAfterOffer, engine.created)
        assertNull(engine.lastRemoteSdp)
    }

    @Test
    fun `malformed inbound frame is dropped silently`() {
        val m = manager()
        m.initiateCall("target-session-b")

        m.onMediaFrame("media:offer", mapOf("type" to "offer", "sessionId" to "s", "sdp" to ""))
        m.onMediaFrame("codec:garbage", mapOf("x" to 1))

        assertEquals(1, engine.created)
    }
}