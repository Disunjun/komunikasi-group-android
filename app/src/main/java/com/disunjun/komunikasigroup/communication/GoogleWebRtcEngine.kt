package com.disunjun.komunikasigroup.communication

import android.content.Context
import com.disunjun.komunikasigroup.domain.CandidateWire
import com.disunjun.komunikasigroup.domain.CommunicationError
import com.disunjun.komunikasigroup.domain.IceServer
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Google WebRTC (io.github.webrtc-sdk:android) engine implementation.
 *
 * Media-policy pinning is F3B-A neutral: no audio capture, no media tracks are
 * added — signaling completion is the gate criterion. The offer/answer carry
 * m-lines via offerToReceiveAudio constraints only, so no microphone permission
 * is required and no PTT/recording exposure is introduced.
 */
class GoogleWebRtcEngine(private val context: Context) : WebRtcEngine {

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var listener: RtcEventListener? = null
    private var remotePeerId: String? = null
    private var closed = false

    override fun initialize(): Result<Unit> = try {
        if (factory == null) {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            factory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options().apply { disableEncryption = false })
                .createPeerConnectionFactory()
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(CommunicationError.Unknown("Gagal menginisialisasi WebRTC engine."))
    }

    override fun createPeerConnection(
        sessionId: String,
        remotePeerId: String,
        listener: RtcEventListener,
        iceServers: List<IceServer>
    ): Result<Unit> {
        return try {
            initialize().getOrThrow()

            val current = peerConnection
            if (current != null) {
                current.close()
                peerConnection = null
            }
            this.remotePeerId = remotePeerId
            this.listener = listener
            closed = false

            val pc = factory!!.createPeerConnection(
                createRtcConfiguration(toNativeIceServers(iceServers)),
                object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                this@GoogleWebRtcEngine.listener?.onRtcEvent(
                    RtcEvent.IceCandidateReady(
                        CandidateWire(
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            candidate = candidate.sdp
                        )
                    )
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                this@GoogleWebRtcEngine.translateConnectionState(newState)
            }

            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onRemoveTrack(receiver: RtpReceiver) {}
            override fun onTrack(transceiver: RtpTransceiver) {}
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            }
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            }
        })

        if (pc == null) {
                return Result.failure(CommunicationError.Unknown("Gagal membuat koneksi WebRTC."))
            }
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            peerConnection = pc
            Result.success(Unit)
        } catch (e: CommunicationError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(CommunicationError.Unknown("Gagal membuat koneksi WebRTC."))
        }
    }

    override fun createOffer(): Result<Unit> = runCatching {
        val pc = peerConnection ?: throw CommunicationError.Unknown("Koneksi WebRTC belum dibuat.")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                setLocalDescription(pc, sdp) {
                    listener?.onRtcEvent(RtcEvent.OfferReady(sdp.description))
                }
            }

            override fun onCreateFailure(error: String) {
                listener?.onRtcEvent(RtcEvent.Failure("Gagal membuat offer."))
            }

            override fun onSetFailure(error: String) {
                listener?.onRtcEvent(RtcEvent.Failure("Gagal memasang offer."))
            }

            override fun onSetSuccess() {}
        }, constraints)
    }

    override fun createAnswer(remoteSdp: String): Result<Unit> = runCatching {
        val pc = peerConnection ?: throw CommunicationError.Unknown("Koneksi WebRTC belum dibuat.")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                setLocalDescription(pc, sdp) {
                    listener?.onRtcEvent(RtcEvent.AnswerReady(sdp.description))
                }
            }

            override fun onCreateFailure(error: String) {
                listener?.onRtcEvent(RtcEvent.Failure("Gagal membuat answer."))
            }

            override fun onSetFailure(error: String) {
                listener?.onRtcEvent(RtcEvent.Failure("Gagal memasang answer."))
            }

            override fun onSetSuccess() {}
        }, constraints)
    }

    override fun setRemoteDescription(sdp: String, type: RemoteDescriptionType): Result<Unit> = runCatching {
        val pc = peerConnection ?: throw CommunicationError.Unknown("Koneksi WebRTC belum dibuat.")
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}

            override fun onCreateFailure(error: String) {}

            override fun onSetSuccess() {
                listener?.onRtcEvent(RtcEvent.SignalingReady)
            }

            override fun onSetFailure(error: String) {
                listener?.onRtcEvent(RtcEvent.Failure("Gagal memasang deskripsi remote."))
            }
        }, SessionDescription(toNativeType(type), sdp))
    }

    override fun addIceCandidate(candidate: CandidateWire): Result<Unit> = runCatching {
        val pc = peerConnection ?: throw CommunicationError.Unknown("Koneksi WebRTC belum dibuat.")
        val native = IceCandidate(
            candidate.sdpMid,
            candidate.sdpMLineIndex,
            candidate.candidate
        )
        if (!pc.addIceCandidate(native)) {
            throw CommunicationError.Unknown("Gagal menambahkan kandidat ICE.")
        }
        Result.success(Unit)
    }

    override fun close(): Result<Unit> {
        if (closed) return Result.success(Unit)
        closed = true
        try {
            peerConnection?.let {
                it.close()
                it.dispose()
            }
        } finally {
            peerConnection = null
            listener = null
            remotePeerId = null
        }
        return Result.success(Unit)
    }

    private fun setLocalDescription(pc: PeerConnection, sdp: SessionDescription, onReady: () -> Unit) {
        pc.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}

            override fun onCreateFailure(error: String) {}

            override fun onSetSuccess() {
                onReady()
            }

            override fun onSetFailure(error: String) {
                listener?.onRtcEvent(RtcEvent.Failure("Gagal memasang deskripsi lokal."))
            }
        }, sdp)
    }

    private fun toNativeType(type: RemoteDescriptionType): SessionDescription.Type =
        when (type) {
            RemoteDescriptionType.OFFER -> SessionDescription.Type.OFFER
            RemoteDescriptionType.ANSWER -> SessionDescription.Type.ANSWER
        }

    /** Map verified domain ICE servers into native servers. Credentials are not logged. */
    private fun toNativeIceServers(servers: List<IceServer>): List<PeerConnection.IceServer> =
        servers.mapNotNull { s ->
            if (s.urls.isEmpty()) return@mapNotNull null
            val native = PeerConnection.IceServer.builder(s.urls)
            if (!s.username.isNullOrBlank()) native.setUsername(s.username)
            if (!s.credential.isNullOrBlank()) native.setPassword(s.credential)
            native.createIceServer()
        }

    private fun translateConnectionState(state: PeerConnection.PeerConnectionState) {
        val mapped = when (state) {
            PeerConnection.PeerConnectionState.NEW -> RtcPeerConnectionState.NEW
            PeerConnection.PeerConnectionState.CONNECTING -> RtcPeerConnectionState.CONNECTING
            PeerConnection.PeerConnectionState.CONNECTED -> RtcPeerConnectionState.CONNECTED
            PeerConnection.PeerConnectionState.DISCONNECTED -> RtcPeerConnectionState.DISCONNECTED
            PeerConnection.PeerConnectionState.FAILED -> RtcPeerConnectionState.FAILED
            PeerConnection.PeerConnectionState.CLOSED -> RtcPeerConnectionState.CLOSED
        }
        listener?.onRtcEvent(RtcEvent.ConnectionStateChanged(mapped))
    }

    /**
     * Build an [PeerConnection.RTCConfiguration] used for every fresh peer connection.
     * ICE servers come from the already-verified TURN credentials; no credential
     * is ever logged here.
     */
    fun createRtcConfiguration(
        iceServers: List<PeerConnection.IceServer> = emptyList()
    ): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
}