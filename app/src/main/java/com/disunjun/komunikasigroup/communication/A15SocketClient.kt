package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.CommunicationError
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class A15SocketState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }

/**
 * Thin Socket.IO transport wrapper for the A1.5 realtime contract.
 * Owns no business logic; it only translates Socket.IO frames into
 * typed callbacks for the adapter.
 */
interface A15SocketCallback {
    fun onSocketState(state: A15SocketState)
    fun onAuthReady(user: JSONObject)
    fun onServerReady(version: String)
    fun onRoomJoined(users: JSONArray, self: JSONObject)
    fun onRoomUsers(users: JSONArray)
    fun onPresenceUpdate(sessions: JSONObject)
    fun onFloorEvent(payload: JSONObject)
    fun onRoomError(message: String)
    fun onAdminKick()
}

class A15SocketClient(
    private val callback: A15SocketCallback,
    private val baseUrl: String = A15Config.baseUrl
) {
    private var socket: Socket? = null

    fun connect(token: String) {
        disconnect(cleanup = false)

        val options = IO.Options.builder()
            .setAuth(mapOf("token" to token))
            .setTransports(arrayOf("websocket", "polling"))
            .setReconnection(true)
            .build()

        val ioSocket = IO.socket(URI.create(baseUrl), options)
        socket = ioSocket

        ioSocket.on(Socket.EVENT_CONNECT) {
            callback.onSocketState(A15SocketState.CONNECTED)
        }
        ioSocket.on(Socket.EVENT_CONNECT_ERROR) {
            callback.onSocketState(A15SocketState.ERROR)
        }
        ioSocket.on(Manager.EVENT_RECONNECT) {
            callback.onSocketState(A15SocketState.CONNECTED)
        }
        ioSocket.on(Manager.EVENT_RECONNECT_ATTEMPT) {
            callback.onSocketState(A15SocketState.RECONNECTING)
        }
        ioSocket.on(Manager.EVENT_RECONNECT_ERROR) {
            callback.onSocketState(A15SocketState.ERROR)
        }
        ioSocket.on(Socket.EVENT_DISCONNECT) {
            callback.onSocketState(A15SocketState.DISCONNECTED)
        }

        ioSocket.on("auth:ready") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            if (payload.optBoolean("ok", false)) {
                payload.optJSONObject("user")?.let { callback.onAuthReady(it) }
            }
        }
        ioSocket.on("server:ready") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            callback.onServerReady(payload.optString("version", ""))
        }
        ioSocket.on("room:joined") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            callback.onRoomJoined(
                payload.optJSONArray("users") ?: JSONArray(),
                payload.optJSONObject("self") ?: JSONObject()
            )
        }
        ioSocket.on("room:users") { args ->
            callback.onRoomUsers(args.firstOrNull() as? JSONArray ?: JSONArray())
        }
        ioSocket.on("presence:update") { args ->
            callback.onPresenceUpdate(args.firstOrNull() as? JSONObject ?: JSONObject())
        }
        ioSocket.on("floor:event") { args ->
            callback.onFloorEvent(args.firstOrNull() as? JSONObject ?: JSONObject())
        }
        ioSocket.on("room:error") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            callback.onRoomError(payload.optString("message", "Gagal bergabung ke channel."))
        }
        ioSocket.on("admin:kick") { callback.onAdminKick() }

        callback.onSocketState(A15SocketState.CONNECTING)
        ioSocket.connect()
    }

    fun joinRoom(group: String, channel: String, username: String, peerId: String, maxUsers: Int) {
        val payload = JSONObject()
            .put("nama", username)
            .put("group", group)
            .put("channel", channel)
            .put("peerId", peerId)
            .put("maxUsers", maxUsers)
        socket?.emit("room:join", payload) ?: throw CommunicationError.Socket("Socket belum terhubung.")
    }

    fun updatePresence(micStatus: Boolean, floorStatus: String) {
        val payload = JSONObject()
            .put("micStatus", micStatus)
            .put("floorStatus", floorStatus)
        socket?.emit("presence:update", payload) ?: throw CommunicationError.Socket("Socket belum terhubung.")
    }

    fun emitFloorEvent(payload: JSONObject) {
        socket?.emit("floor:event", payload) ?: throw CommunicationError.Socket("Socket belum terhubung.")
    }

    fun disconnect(cleanup: Boolean = true) {
        val current = socket ?: return
        socket = null
        current.disconnect()
        if (cleanup) current.close()
    }

    val isConnected: Boolean
        get() = socket?.connected() == true
}