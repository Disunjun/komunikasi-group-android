package com.disunjun.komunikasigroup.media

import android.content.Context
import org.webrtc.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaSignalingManager handles WebRTC media session lifecycle.
 * 
 * E1 — Media session replacement:
 * - If current media session is CONNECTED, reject as busy.
 * - If current session is NEW/SIGNALING/NEGOTIATING/FAILED/DISCONNECTED/CLOSED,
 *   safely terminate and release the old session.
 * - Clear old callbacks/resources.
 * - Accept the new inbound offer.
 * - Create a fresh MediaSession/PeerConnection.
 * - Prevent stale callbacks from the old PeerConnection from affecting the new session.
 */
class MediaSignalingManager(
    private val context: Context,
    private val rtcConfig: Map<String, Any> = emptyMap()
) {
    companion object {
        private const val TAG = "MediaSignalingManager"
    }

    enum class SessionState {
        NEW,
        SIGNALING,
        NEGOTIATING,
        CONNECTED,
        FAILED,
        DISCONNECTED,
        CLOSED
    }

    data class MediaSessionInfo(
        val sessionId: String,
        val state: SessionState,
        val createdAt: Long = System.currentTimeMillis()
    )

    @Volatile
    private var currentSession: MediaSessionInternal? = null

    @Volatile
    private var currentState: SessionState = SessionState.CLOSED

    private val sessionLock = Object()

    private val isInitialized = AtomicBoolean(false)
    private var eglContext: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null

    /**
     * Initialize the WebRTC peer connection factory.
     * Must be called before handling offers.
     */
    fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            eglContext = EglBase.create()
            
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = false
                })
                .createPeerConnectionFactory()
        }
    }

    /**
     * Handle an inbound offer.
     * 
     * E1 Implementation:
     * - Check current session state
     * - If CONNECTED, reject as busy
     * - Otherwise, safely terminate old session and create new one
     */
    fun onInboundOffer(
        offerSdp: String,
        remoteSessionId: String,
        callback: OfferCallback
    ): Result<Unit> {
        synchronized(sessionLock) {
            // Check current session state
            val currentStateSnapshot = currentState
            
            when (currentStateSnapshot) {
                SessionState.CONNECTED -> {
                    // Reject as busy - existing call is active
                    return Result.failure(SessionBusyException("Existing call is connected. Cannot accept new offer."))
                }
                SessionState.NEW,
                SessionState.SIGNALING,
                SessionState.NEGOTIATING,
                SessionState.FAILED,
                SessionState.DISCONNECTED,
                SessionState.CLOSED -> {
                    // Safely terminate and release old session
                    cleanupCurrentSession()
                }
            }

            // Ensure initialization
            if (!isInitialized.get()) {
                initialize()
            }

            // Create fresh session with unique ID
            val newSessionId = UUID.randomUUID().toString()
            val newSession = MediaSessionInternal(
                sessionId = newSessionId,
                remoteSessionId = remoteSessionId,
                peerConnectionFactory = peerConnectionFactory ?: run {
                    return Result.failure(IllegalStateException("PeerConnectionFactory not initialized"))
                },
                eglContext = eglContext?.eglBaseContext,
                stateCallback = object : MediaSessionStateCallback {
                    override fun onStateChanged(newState: SessionState) {
                        // Only update state if this session is still current
                        synchronized(sessionLock) {
                            if (currentSession?.sessionId == newSessionId) {
                                currentState = newState
                            }
                        }
                        callback.onSessionStateChanged(newSessionId, newState)
                    }

                    override fun onIceCandidate(candidate: IceCandidate) {
                        // Only forward if this session is still current
                        synchronized(sessionLock) {
                            if (currentSession?.sessionId == newSessionId) {
                                callback.onIceCandidate(newSessionId, candidate)
                            }
                        }
                    }

                    override fun onRemoteDescription(description: SessionDescription) {
                        // Only forward if this session is still current
                        synchronized(sessionLock) {
                            if (currentSession?.sessionId == newSessionId) {
                                callback.onRemoteDescription(newSessionId, description)
                            }
                        }
                    }

                    override fun onConnected() {
                        // Only forward if this session is still current
                        synchronized(sessionLock) {
                            if (currentSession?.sessionId == newSessionId) {
                                currentState = SessionState.CONNECTED
                                callback.onConnected(newSessionId)
                            }
                        }
                    }

                    override fun onFailed(error: String) {
                        // Only forward if this session is still current
                        synchronized(sessionLock) {
                            if (currentSession?.sessionId == newSessionId) {
                                currentState = SessionState.FAILED
                                callback.onFailed(newSessionId, error)
                            }
                        }
                    }

                    override fun onDisconnected() {
                        // Only forward if this session is still current
                        synchronized(sessionLock) {
                            if (currentSession?.sessionId == newSessionId) {
                                currentState = SessionState.DISCONNECTED
                                callback.onDisconnected(newSessionId)
                            }
                        }
                    }

                    override fun onClosed() {
                        // Only clear state if this session is still current
                        synchronized(sessionLock) {
                            if (currentSession?.sessionId == newSessionId) {
                                currentState = SessionState.CLOSED
                                currentSession = null
                                callback.onClosed(newSessionId)
                            }
                        }
                    }
                }
            )

            // Set current session BEFORE processing offer to prevent race conditions
            currentSession = newSession
            currentState = SessionState.SIGNALING

            // Process the inbound offer
            return newSession.processOffer(offerSdp)
                .mapCatching { answerSdp ->
                    callback.onAnswerGenerated(newSessionId, answerSdp)
                }
        }
    }

    /**
     * Safely cleanup the current session.
     * Clears callbacks and releases resources to prevent stale callbacks
     * from affecting future sessions.
     */
    private fun cleanupCurrentSession() {
        currentSession?.let { session ->
            val sessionIdToCleanup = session.sessionId
            
            // Nullify current session reference first to prevent callbacks
            val sessionToCleanup = currentSession
            currentSession = null
            currentState = SessionState.CLOSED
            
            // Release resources asynchronously to avoid blocking
            sessionToCleanup?.release()
        }
    }

    /**
     * Get current session info for monitoring/debugging.
     */
    fun getCurrentSessionInfo(): MediaSessionInfo? {
        synchronized(sessionLock) {
            return currentSession?.let { session ->
                MediaSessionInfo(
                    sessionId = session.sessionId,
                    state = currentState,
                    createdAt = session.createdAt
                )
            }
        }
    }

    /**
     * Get current state.
     */
    fun getCurrentState(): SessionState = currentState

    /**
     * Handle ICE candidate from remote.
     */
    fun addIceCandidate(sessionId: String, candidate: IceCandidate): Boolean {
        synchronized(sessionLock) {
            if (currentSession?.sessionId == sessionId) {
                return currentSession?.addIceCandidate(candidate) == true
            }
            return false
        }
    }

    /**
     * Set remote description (answer).
     */
    fun setRemoteDescription(sessionId: String, description: SessionDescription): Result<Unit> {
        synchronized(sessionLock) {
            if (currentSession?.sessionId == sessionId) {
                return currentSession?.setRemoteDescription(description) ?: Result.failure(IllegalStateException("No session"))
            }
            return Result.failure(IllegalStateException("Session ID mismatch"))
        }
    }

    /**
     * End the current call gracefully.
     */
    fun endCall() {
        synchronized(sessionLock) {
            cleanupCurrentSession()
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        synchronized(sessionLock) {
            cleanupCurrentSession()
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            eglContext?.release()
            eglContext = null
            isInitialized.set(false)
            currentState = SessionState.CLOSED
        }
    }

    /**
     * Callback interface for media session events.
     */
    interface OfferCallback {
        fun onAnswerGenerated(sessionId: String, answerSdp: String)
        fun onSessionStateChanged(sessionId: String, newState: SessionState)
        fun onIceCandidate(sessionId: String, candidate: IceCandidate)
        fun onRemoteDescription(sessionId: String, description: SessionDescription)
        fun onConnected(sessionId: String)
        fun onFailed(sessionId: String, error: String)
        fun onDisconnected(sessionId: String)
        fun onClosed(sessionId: String)
    }

    /**
     * Internal media session representation.
     * Encapsulates PeerConnection and prevents external access to stale references.
     */
    private class MediaSessionInternal(
        val sessionId: String,
        val remoteSessionId: String,
        private val peerConnectionFactory: PeerConnectionFactory,
        private val eglContext: EglBase.Context?,
        private val stateCallback: MediaSessionStateCallback
    ) {
        val createdAt: Long = System.currentTimeMillis()

        @Volatile
        private var peerConnection: RTCPeerConnection? = null

        @Volatile
        private var isReleased = false

        private val pcLock = Object()

        // Create PeerConnection observer that forwards only to non-released sessions
        private val pcObserver = object : Observer {
            override fun onSignalingChange(newState: RTCPeerConnection.SignalingState) {
                if (isReleased) return
                when (newState) {
                    RTCPeerConnection.SignalingState.STABLE -> {
                        // Negotiation complete
                    }
                    RTCPeerConnection.SignalingState.HAVE_LOCAL_OFFER,
                    RTCPeerConnection.SignalingState.HAVE_REMOTE_OFFER -> {
                        stateCallback.onStateChanged(SessionState.NEGOTIATING)
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionChange(newState: RTCPeerConnection.IceConnectionState) {
                if (isReleased) return
                when (newState) {
                    RTCPeerConnection.IceConnectionState.CONNECTED -> {
                        stateCallback.onConnected()
                    }
                    RTCPeerConnection.IceConnectionState.DISCONNECTED,
                    RTCPeerConnection.IceConnectionState.FAILED -> {
                        stateCallback.onFailed("ICE connection ${newState}")
                    }
                    RTCPeerConnection.IceConnectionState.CLOSED -> {
                        stateCallback.onClosed()
                    }
                    else -> {}
                }
            }

            override fun onIceGatheringChange(newState: RTCPeerConnection.IceGatheringState) {
                if (isReleased) return
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (isReleased) return
                candidate?.let { stateCallback.onIceCandidate(it) }
            }

            override fun onTrack(transceiver: RTCRtpTransceiver?) {
                if (isReleased) return
                // Handle remote track if needed
            }

            override fun onAddStream(stream: MediaStream?) {
                if (isReleased) return
            }

            override fun onRemoveStream(stream: MediaStream?) {
                if (isReleased) return
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                if (isReleased) return
            }

            override fun onRenegotiationNeeded() {
                if (isReleased) return
                stateCallback.onStateChanged(SessionState.NEGOTIATING)
            }

            override fun onConnectionChange(newState: RTCPeerConnection.PeerConnectionState) {
                if (isReleased) return
                when (newState) {
                    RTCPeerConnection.PeerConnectionState.CONNECTED -> {
                        stateCallback.onConnected()
                    }
                    RTCPeerConnection.PeerConnectionState.DISCONNECTED -> {
                        stateCallback.onDisconnected()
                    }
                    RTCPeerConnection.PeerConnectionState.FAILED -> {
                        stateCallback.onFailed("Peer connection failed")
                    }
                    RTCPeerConnection.PeerConnectionState.CLOSED -> {
                        stateCallback.onClosed()
                    }
                    else -> {}
                }
            }
        }

        init {
            createPeerConnection()
        }

        private fun createPeerConnection() {
            synchronized(pcLock) {
                if (isReleased) return

                val rtcConfig = RTCConfiguration(emptyList()).apply {
                    sdpSemantics = SDPSemantics.UNIFIED_PLAN
                    combinedAudioVideoBwe = true
                }

                peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, pcObserver)
            }
        }

        fun processOffer(offerSdp: String): Result<String> {
            synchronized(pcLock) {
                if (isReleased) {
                    return Result.failure(IllegalStateException("Session released"))
                }

                val peerConn = peerConnection ?: return Result.failure(IllegalStateException("No PeerConnection"))

                // Set remote description (offer)
                val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

                // Use CountDownLatch or similar for async operation
                var result: Result<String>? = null

                peerConn.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        // Not used for remote description
                    }

                    override fun onSetSuccess() {
                        // Remote description set successfully, create answer
                        peerConn.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription?) {
                                desc?.let { answerDesc ->
                                    // Set local description (answer)
                                    peerConn.setLocalDescription(object : SdpObserver {
                                        override fun onCreateSuccess(desc: SessionDescription?) {}
                                        override fun onSetSuccess() {
                                            result = Result.success(answerDesc.description)
                                        }
                                        override fun onCreateFailure(p0: String?) {}
                                        override fun onSetFailure(p0: String?) {}
                                    }, answerDesc)
                                }
                            }

                            override fun onSetSuccess() {}
                            override fun onCreateFailure(error: String?) {
                                result = Result.failure(Exception("Failed to create answer: $error"))
                            }
                            override fun onSetFailure(error: String?) {}
                        }, MediaConstraints())
                    }

                    override fun onCreateFailure(error: String?) {
                        result = Result.failure(Exception("Failed to process offer: $error"))
                    }

                    override fun onSetFailure(error: String?) {
                        result = Result.failure(Exception("Failed to set remote description: $error"))
                    }
                }, remoteDesc)

                // Wait for async operation (in real implementation, use coroutines/callbacks)
                // For now, return success and let callback handle the answer
                return Result.success("")
            }
        }

        fun addIceCandidate(candidate: IceCandidate): Boolean {
            synchronized(pcLock) {
                if (isReleased) return false
                return peerConnection?.addIceCandidate(candidate) == true
            }
        }

        fun setRemoteDescription(description: SessionDescription): Result<Unit> {
            synchronized(pcLock) {
                if (isReleased) return Result.failure(IllegalStateException("Session released"))

                var result: Result<Unit>? = null
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onSetSuccess() {
                        result = Result.success(Unit)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, description)

                return result ?: Result.success(Unit)
            }
        }

        fun release() {
            synchronized(pcLock) {
                if (isReleased) return
                isReleased = true

                peerConnection?.close()
                peerConnection?.dispose()
                peerConnection = null
            }
        }
    }

    /**
     * Internal callback interface for session state changes.
     */
    private interface MediaSessionStateCallback {
        fun onStateChanged(newState: SessionState)
        fun onIceCandidate(candidate: IceCandidate)
        fun onRemoteDescription(description: SessionDescription)
        fun onConnected()
        fun onFailed(error: String)
        fun onDisconnected()
        fun onClosed()
    }

    /**
     * Exception thrown when trying to start a new call while one is connected.
     */
    class SessionBusyException(message: String) : Exception(message)
}
