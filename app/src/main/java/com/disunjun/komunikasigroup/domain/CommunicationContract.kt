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

/**
 * ICE/TURN server descriptor produced from the A1.5 GET /api/turn-credentials
 * contract. Credentials are short-lived (ttl, typically 86400s) and live only
 * in memory for the active media session; they must never be persisted or logged.
 */
data class IceServer(
    val urls: List<String>,
    val username: String?,
    val credential: String?
)

/** Parsed A1.5 TURN credential response. */
data class TurnCredentials(
    val ttl: Long?,
    val iceServers: List<IceServer>
)

/**
 * Pure A1.5 GET /api/turn-credentials parser. Free of Android/HTTP types so the
 * domain contract is unit-testable on the JVM. Transport adapters map their raw
 * JSON into Map<String, Any?> and delegate here.
 */
object TurnCredentialParser {
    fun parse(payload: Map<String, Any?>): Result<TurnCredentials> {
        if (payload["ok"] == false) {
            return Result.failure(
                CommunicationError.Turn(
                    payload["message"]?.toString().orEmpty().ifBlank { "Respons TURN tidak valid." }
                )
            )
        }
        val rawServers = payload["iceServers"] ?: emptyList<Any?>()
        val servers = (rawServers as? List<*>)
            ?.mapIndexedNotNull { index, raw ->
                val entry = raw as? Map<*, *> ?: return@mapIndexedNotNull null
                val urls = when (val u = entry["urls"]) {
                    is String -> listOf(u)
                    is List<*> -> u.mapNotNull { it as? String }
                    else -> null
                }
                val cleanUrls = urls?.map { it.trim() }?.filter { it.isNotEmpty() }
                if (cleanUrls.isNullOrEmpty()) {
                    return@mapIndexedNotNull null
                }
                IceServer(
                    urls = cleanUrls,
                    username = entry["username"] as? String,
                    credential = entry["credential"] as? String
                )
            }
            ?: emptyList()

        if (servers.isEmpty()) {
            return Result.failure(
                CommunicationError.Turn("Respons TURN tidak berisi ICE servers yang valid.")
            )
        }

        val ttl = (payload["ttl"] as? Number)?.toLong()
        return Result.success(TurnCredentials(ttl = ttl, iceServers = servers))
    }
}

/**
 * Fetches short-lived TURN/ICE credentials for the current media session.
 * Implementations must not log credentials or persist them beyond the call.
 */
interface TurnCredentialProvider {
    fun getTurnCredentials(): Result<TurnCredentials>
}

/** Coarse media-call lifecycle surfaced to the UI/service layer. */
enum class MediaCallStage {
    OFFLINE, LOGGING_IN, JOINING_CHANNEL, SIGNALING, NEGOTIATING, CONNECTED, CLOSED, FAILED
}

/** Non-secret snapshot of the media call state. Never carries SDP/ICE/token data. */
data class MediaCallSnapshot(
    val stage: MediaCallStage,
    val error: String? = null
)

/** A v3 channel participant addressable as a media target by its v3 sessionId. */
data class MediaPeer(
    val name: String,
    val sessionId: String
)

/**
 * Non-secret identity from a v3 media login. The raw JWT emitted by the v3
 * /api/auth/login endpoint is kept inside the adapter only and never crosses
 * the domain boundary (nor logs/persistence).
 */
data class V3MediaIdentity(
    val name: String,
    val sessionId: String
)

/** Domain-level failure. No HTTP/Socket.IO implementation details leak out of adapters. */
sealed class CommunicationError(override val message: String) : Exception(message) {
    class Authentication(message: String) : CommunicationError(message)
    class Network(message: String) : CommunicationError(message)
    class Socket(message: String) : CommunicationError(message)
    class Room(message: String) : CommunicationError(message)
    class Turn(message: String) : CommunicationError(message)
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

    // ---- Media-signaling (v3) extensions ----
    suspend fun mediaLogin(name: String, password: String): Result<V3MediaIdentity>
    suspend fun mediaJoinChannel(channelId: String): Result<Unit>
    fun startMediaCall(remoteSessionId: String): Result<Unit>
    fun stopMediaCall(): Result<Unit>
    fun setMediaSignalingListener(listener: MediaSignalingListener?)
}

/** Recording Contract V3 boundary: UI/service never depends on a concrete recorder. */
interface RecordingPort {
    fun start(): Result<Unit>
    fun stop(): Result<Unit>
}

/** Media-signaling notifications pushed from the adapter to the UI/service layer. */
interface MediaSignalingListener {
    fun onMediaCallSnapshot(snapshot: MediaCallSnapshot)
    fun onMediaPeers(peers: List<MediaPeer>)
}