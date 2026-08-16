package com.disunjun.komunikasigroup.domain

/** Stable domain language for Android V1. Transport is intentionally behind this contract. */
enum class ConnectionState { OFFLINE, CONNECTING, ONLINE, RECONNECTING, DISCONNECTED, ERROR }
enum class PttState { IDLE, TRANSMITTING }
enum class RecordingState { IDLE, RECORDING, STOPPED, ERROR }

/** Authenticated user as returned by the A1.5 auth contract. */
data class AuthUser(
    val id: Long?,
    val nama: String,
    val role: String,
    val status: String,
    val banned: Boolean,
    val muted: Boolean
)

/** Opaque channel to join on the A1.5 server room:join contract. */
data class ChannelTarget(
    val group: String,
    val channel: String,
    val username: String,
    val peerId: String,
    val maxUsers: Int = 0
)

/** Presence entry consumed from the A1.5 presence contract. */
data class PresenceInfo(
    val username: String,
    val group: String?,
    val channel: String?,
    val peerId: String?,
    val micStatus: Boolean,
    val floorStatus: String
)

/** Floor state mirroring the A1.5 floor:event payload ({jenis:"floorState"}). */
data class FloorState(
    val currentTalker: String?,
    val queue: List<String>,
    val lastUpdate: Long
)

/**
 * Pure A1.5 floor:event parser. Kept free of Android/HTTP types so the domain
 * contract is unit-testable on the JVM. Transport adapters map their raw JSON
 * into Map<String, Any?> and delegate here.
 */
object FloorParser {
    fun parseFloorState(payload: Map<String, Any?>): FloorState? {
        if (payload["jenis"] != "floorState") return null
        val state = payload["state"] as? Map<*, *> ?: return null
        val currentTalker = state["currentTalker"] as? String
        val queue = (state["queue"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val lastUpdate = (state["lastUpdate"] as? Number)?.toLong() ?: 0L
        return FloorState(currentTalker = currentTalker, queue = queue, lastUpdate = lastUpdate)
    }
}

/** Domain-level failure. No HTTP/Socket.IO implementation details leak out of adapters. */
sealed class CommunicationError(override val message: String) : Exception(message) {
    class Authentication(message: String) : CommunicationError(message)
    class Network(message: String) : CommunicationError(message)
    class Socket(message: String) : CommunicationError(message)
    class Room(message: String) : CommunicationError(message)
    class Unknown(message: String) : CommunicationError(message)
}

/** Async notifications pushed from the transport adapter to the UI/service layer. */
interface CommunicationListener {
    fun onConnectionState(state: ConnectionState)
    fun onAuthReady(user: AuthUser)
    fun onServerReady(version: String)
    fun onRoomJoined(members: List<PresenceInfo>)
    fun onRoomUsers(members: List<PresenceInfo>)
    fun onPresenceUpdated(sessions: List<PresenceInfo>)
    fun onFloorEvent(state: FloorState)
    fun onError(error: CommunicationError)
}

data class CommunicationState(
    val connection: ConnectionState = ConnectionState.OFFLINE,
    val ptt: PttState = PttState.IDLE,
    val recording: RecordingState = RecordingState.IDLE,
    val channelId: String = "CH-01",
    val error: String? = null
)

interface CommunicationPort {
    suspend fun connect(channelId: String): Result<Unit>
    suspend fun disconnect()
    fun startPtt()
    fun stopPtt()

    // ---- A1.5 control-plane extensions ----
    suspend fun login(nama: String, sandi: String): Result<AuthUser>
    suspend fun restoreSession(): Result<AuthUser>
    suspend fun logout(): Result<Unit>
    suspend fun joinRoom(target: ChannelTarget): Result<Unit>
    fun updatePresence(micStatus: Boolean, floorStatus: String)
    fun setListener(listener: CommunicationListener?)
}

/** Recording Contract V3 boundary: UI/service never depends on a concrete recorder. */
interface RecordingPort {
    fun start(): Result<Unit>
    fun stop(): Result<Unit>
}