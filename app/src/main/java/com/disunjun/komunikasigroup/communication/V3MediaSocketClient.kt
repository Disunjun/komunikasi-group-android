package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.CommunicationError
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject

/** Connectivity state of the v3 media signaling socket. */
enum class MediaSocketState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }

interface MediaSocketCallback {
    fun onMediaSocketState(state: MediaSocketState)
    fun onChannelJoined(channel: JSONObject)
    fun onChannelError(message: String)
    fun onChannelPeers(peers: JSONArray)
}

/** A v3 login response: opaque JWT plus the authenticated user's metadata. */
data class V3MediaSession(
    val token: String,
    val sessionId: String,
    val name: String
)

/**
 * Thin Socket.IO transport for the v3 media signaling contract.
 *
 * Same philosophy as [A15SocketClient]: no business logic, only translation of
 * Socket.IO frames into typed signals for the MediaSignalingManager bridge.
 * Connects to [A15Config.signalingUrl]; authentication uses the v3 JWT issued
 * by [V3MediaAuthenticator.login].
 */
class V3MediaSocketClient(
    private val mediaCallback: MediaSocketCallback,
    private val frameListener: MediaSignalTransportListener = MediaSignalTransportListener { _, _ -> },
    private val baseUrl: String = A15Config.signalingUrl
) : MediaSignalTransport {

    private var socket: Socket? = null

    fun connect(token: String) {
        disconnect()

        val options = IO.Options.builder()
            .setAuth(mapOf("token" to token))
            .setTransports(arrayOf("websocket", "polling"))
            .setReconnection(true)
            .build()

        val ioSocket = IO.socket(java.net.URI.create(baseUrl), options)
        socket = ioSocket

        ioSocket.on(Socket.EVENT_CONNECT) {
            mediaCallback.onMediaSocketState(MediaSocketState.CONNECTED)
        }
        ioSocket.on(Socket.EVENT_CONNECT_ERROR) {
            mediaCallback.onMediaSocketState(MediaSocketState.ERROR)
        }
        ioSocket.on(Manager.EVENT_RECONNECT_ATTEMPT) {
            mediaCallback.onMediaSocketState(MediaSocketState.RECONNECTING)
        }
        ioSocket.on(Socket.EVENT_DISCONNECT) {
            mediaCallback.onMediaSocketState(MediaSocketState.DISCONNECTED)
        }

        ioSocket.on("channel:joined") { args ->
            mediaCallback.onChannelJoined(args.firstOrNull() as? JSONObject ?: JSONObject())
        }
        ioSocket.on("channel:error") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            mediaCallback.onChannelError(payload.optString("error", "Gagal bergabung ke channel."))
        }
        ioSocket.on("voice:peers") { args ->
            mediaCallback.onChannelPeers(args.firstOrNull() as? JSONArray ?: JSONArray())
        }

        ioSocket.on("media:offer") { args -> forwardFrame("media:offer", args) }
        ioSocket.on("media:answer") { args -> forwardFrame("media:answer", args) }
        ioSocket.on("media:ice-candidate") { args -> forwardFrame("media:ice-candidate", args) }
        ioSocket.on("media:leave") { args -> forwardFrame("media:leave", args) }
        ioSocket.on("media:error") { args -> forwardFrame("media:error", args) }

        mediaCallback.onMediaSocketState(MediaSocketState.CONNECTING)
        ioSocket.connect()
    }

    private fun forwardFrame(event: String, args: Array<out Any?>) {
        val payload = args.firstOrNull() as? JSONObject ?: return
        frameListener.onMediaFrame(event, payload.toFlatMap())
    }

    /** MediaSignalTransport — join the v3 channel that hosts the media session. */
    override fun joinChannel(channelId: String) {
        socket?.emit("channel:join", JSONObject().put("channelId", channelId))
            ?: throw CommunicationError.Socket("Socket media belum terhubung.")
    }

    override fun isConnected(): Boolean = socket?.connected() == true

    override fun emit(event: String, payload: Map<String, Any?>) {
        socket?.emit(event, JSONObject(payload))
            ?: throw CommunicationError.Socket("Socket media belum terhubung.")
    }

    override fun disconnect() {
        val current = socket ?: return
        socket = null
        current.disconnect()
        current.close()
    }
}