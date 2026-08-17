package com.disunjun.komunikasigroup.communication

import android.content.Context
import android.content.SharedPreferences
import com.disunjun.komunikasigroup.domain.AuthUser
import com.disunjun.komunikasigroup.domain.ChannelTarget
import com.disunjun.komunikasigroup.domain.CommunicationError
import com.disunjun.komunikasigroup.domain.CommunicationListener
import com.disunjun.komunikasigroup.domain.CommunicationPort
import com.disunjun.komunikasigroup.domain.ConnectionState
import com.disunjun.komunikasigroup.domain.FloorParser
import com.disunjun.komunikasigroup.domain.FloorState
import com.disunjun.komunikasigroup.domain.MediaCallSnapshot
import com.disunjun.komunikasigroup.domain.MediaCallStage
import com.disunjun.komunikasigroup.domain.MediaErrorCode
import com.disunjun.komunikasigroup.domain.MediaPeer
import com.disunjun.komunikasigroup.domain.MediaSession
import com.disunjun.komunikasigroup.domain.MediaSessionState
import com.disunjun.komunikasigroup.domain.MediaSignalingListener
import com.disunjun.komunikasigroup.domain.PresenceInfo
import com.disunjun.komunikasigroup.domain.PttState
import com.disunjun.komunikasigroup.domain.V3MediaIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Real A1.5 control-plane transport adapter.
 *
 * Implements the domain contract (CommunicationPort) and never exposes REST or
 * Socket.IO details to the UI/service layer. Networking lives here (and in its
 * collaborators), NOT in the ForegroundService.
 */
class A15CommunicationAdapter(context: Context) : CommunicationPort, A15SocketCallback, MediaSocketCallback, MediaSessionEventListener {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("komunikasi_group_runtime", Context.MODE_PRIVATE)
    private val tokenStore = TokenStore(appContext)
    private val restClient = A15RestClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val socketClient by lazy { A15SocketClient(this) }

    private var listener: CommunicationListener? = null
    private var currentUser: AuthUser? = null
    private var roomJoined = false
    private var lastFloor = FloorState(currentTalker = null, queue = emptyList(), lastUpdate = 0L)

    private val v3Authenticator = V3MediaAuthenticator()
    private var mediaSocket: V3MediaSocketClient? = null
    private var mediaListener: MediaSignalingListener? = null
    private var mediaManager: MediaSignalingManager? = null
    private var mediaChannelId: String? = null
    private var localPeerId: String? = null

    // ---- media signaling (v3) extensions ----

    override suspend fun mediaLogin(name: String, password: String): Result<V3MediaIdentity> {
        return v3Authenticator.login(name, password).map { session ->
            val identity = V3MediaIdentity(name = session.name, sessionId = session.sessionId)
            val socket = V3MediaSocketClient(
                mediaCallback = this,
                frameListener = MediaSignalTransportListener { event, payload ->
                    mediaManager?.onMediaFrame(event, payload)
                }
            )
            mediaSocket = socket
            socket.connect(session.token)
            emitMediaCall(MediaCallStage.LOGGING_IN)
            identity
        }
    }

    override suspend fun mediaJoinChannel(channelId: String): Result<Unit> {
        val socket = mediaSocket ?: return Result.failure(
            CommunicationError.Authentication("Login media terlebih dahulu.")
        )
        return runCatching {
            mediaChannelId = channelId
            emitMediaCall(MediaCallStage.JOINING_CHANNEL)
            socket.joinChannel(channelId)
        }.let { r ->
            r.onFailure { e ->
                listener?.onError(CommunicationError.Socket(e.message ?: "Gagal masuk channel media."))
                emitMediaCall(MediaCallStage.FAILED, e.message)
            }
            r.map { Unit }
        }
    }

    override fun startMediaCall(remoteSessionId: String): Result<Unit> {
        val manager = mediaManager ?: return Result.failure(
            CommunicationError.Room("Belum masuk channel media.")
        )
        return manager.initiateCall(remoteSessionId)
    }

    override fun stopMediaCall(): Result<Unit> {
        val manager = mediaManager ?: return Result.success(Unit)
        val result = manager.leave()
        emitMediaCall(MediaCallStage.CLOSED)
        return result
    }

    override fun setMediaSignalingListener(listener: MediaSignalingListener?) {
        mediaListener = listener
    }

    // ---- MediaSocketCallback ----

    override fun onMediaSocketState(state: MediaSocketState) {
        if (state == MediaSocketState.CONNECTED) {
            emitMediaCall(MediaCallStage.LOGGING_IN)
        }
    }

    override fun onChannelJoined(channel: JSONObject) {
        val channelId = channel.optString("id").ifBlank {
            mediaChannelId ?: return@onChannelJoined
        }
        val groupId = channel.optString("group_id").ifBlank { "Grup 1" }
        val socket = mediaSocket ?: return
        val manager = MediaSignalingManager(
            engine = GoogleWebRtcEngine(appContext),
            transport = socket,
            turnCredentialProvider = CommunicationRuntime.turnCredentials(appContext),
            localPeerId = requirePeerId(),
            group = groupId,
            channel = channelId
        )
        manager.setListener(this)
        mediaManager = manager
        emitMediaCall(MediaCallStage.SIGNALING)
    }

    override fun onChannelError(message: String) {
        listener?.onError(CommunicationError.Room(message))
        emitMediaCall(MediaCallStage.FAILED, message)
    }

    override fun onChannelPeers(peers: JSONArray) {
        val list = List(peers.length()) { i ->
            val p = peers.optJSONObject(i) ?: return@List MediaPeer(name = "", sessionId = "")
            MediaPeer(name = p.optString("name", ""), sessionId = p.optString("sessionId", ""))
        }.filter { it.name.isNotBlank() && it.sessionId.isNotBlank() }
        emitMediaPeers(list)
    }

    // ---- MediaSessionEventListener ----

    override fun onMediaSessionState(state: MediaSession) {
        val stage = when (state.state) {
            MediaSessionState.NEW, MediaSessionState.SIGNALING -> MediaCallStage.SIGNALING
            MediaSessionState.CONNECTING -> MediaCallStage.NEGOTIATING
            MediaSessionState.CONNECTED -> MediaCallStage.CONNECTED
            MediaSessionState.DISCONNECTED -> MediaCallStage.CLOSED
            MediaSessionState.FAILED -> MediaCallStage.FAILED
            MediaSessionState.CLOSED -> MediaCallStage.CLOSED
        }
        emitMediaCall(stage)
    }

    override fun onMediaError(code: MediaErrorCode?, message: String) {
        emitMediaCall(MediaCallStage.FAILED, message)
    }

    private fun emitMediaCall(stage: MediaCallStage, message: String? = null) {
        mediaListener?.onMediaCallSnapshot(MediaCallSnapshot(stage = stage, error = message))
    }

    private fun emitMediaPeers(peers: List<MediaPeer>) {
        mediaListener?.onMediaPeers(peers)
    }

    override fun setListener(listener: CommunicationListener?) {
        this.listener = listener
    }

    override suspend fun login(nama: String, sandi: String): Result<AuthUser> {
        return restClient.login(nama, sandi).map { session ->
            tokenStore.saveToken(session.token)
            currentUser = session.user
            listener?.onAuthReady(session.user)
            session.user
        }
    }

    override suspend fun restoreSession(): Result<AuthUser> {
        val token = tokenStore.getToken()
            ?: return Result.failure(CommunicationError.Authentication("Belum ada sesi login."))

        return restClient.me(token).map { user ->
            currentUser = user
            listener?.onAuthReady(user)
            user
        }.recoverCatching { e ->
            if (e is CommunicationError.Authentication) tokenStore.clear()
            throw e
        }
    }

    override suspend fun logout(): Result<Unit> {
        val token = tokenStore.getToken()
        val result = if (token.isNullOrBlank()) {
            Result.success(Unit)
        } else {
            restClient.logout(token)
        }
        tokenStore.clear()
        socketClient.disconnect()
        roomJoined = false
        emitConnection(ConnectionState.OFFLINE)
        return result
    }

    override suspend fun joinRoom(target: ChannelTarget): Result<Unit> {
        val user = currentUser ?: run {
            val restored = restoreSession()
            restored.getOrNull() ?: return restored.map { }
        }
        val username = target.username.ifBlank { user.nama }
        return runCatching {
            socketClient.connectIfNeeded { tokenProvider() }
            socketClient.joinRoom(target.group, target.channel, username, target.peerId, target.maxUsers)
            roomJoined = true
        }.let { r ->
            r.onFailure { e ->
                listener?.onError(
                    CommunicationError.Socket(e.message ?: "Gagal menghubungkan socket.")
                )
            }
            r.map { Unit }
        }
    }

    private fun tokenProvider(): String =
        tokenStore.getToken() ?: throw CommunicationError.Authentication("Belum ada sesi login.")

    override suspend fun connect(channelId: String): Result<Unit> {
        emitConnection(ConnectionState.CONNECTING)
        val user = currentUser ?: restoreSession().getOrNull()
        if (user == null) {
            val error = CommunicationError.Authentication("Login diperlukan sebelum masuk channel.")
            emitConnection(ConnectionState.ERROR)
            listener?.onError(error)
            return Result.failure(error)
        }
        val peerId = requirePeerId()
        return joinRoom(
            ChannelTarget(
                group = "Grup 1",
                channel = channelId,
                username = user.nama,
                peerId = peerId
            )
        )
    }

    override suspend fun disconnect() {
        socketClient.disconnect()
        roomJoined = false
        emitConnection(ConnectionState.OFFLINE)
    }

    override fun updatePresence(micStatus: Boolean, floorStatus: String) {
        runCatching { socketClient.updatePresence(micStatus, floorStatus) }
            .onFailure { e ->
                listener?.onError(CommunicationError.Socket(e.message ?: "Gagal mengirim presence."))
            }
    }

    override fun startPtt() {
        if (!roomJoined) {
            listener?.onError(CommunicationError.Room("Belum masuk channel."))
            return
        }

        val me = currentUser?.nama ?: return
        val now = System.currentTimeMillis()
        lastFloor = when {
            lastFloor.currentTalker == null -> FloorState(me, emptyList(), now)
            lastFloor.currentTalker == me -> lastFloor
            else -> FloorState(lastFloor.currentTalker, lastFloor.queue + me, now)
        }
        broadcastFloor(lastFloor)
        updatePresence(micStatus = lastFloor.currentTalker == me, floorStatus = "talking")
        emitPtt(PttState.TRANSMITTING)
    }

    override fun stopPtt() {
        if (!roomJoined) return
        val me = currentUser?.nama ?: return
        val now = System.currentTimeMillis()

        lastFloor = when {
            lastFloor.currentTalker == me -> FloorState(
                currentTalker = lastFloor.queue.firstOrNull(),
                queue = lastFloor.queue.drop(1),
                lastUpdate = now
            )
            else -> FloorState(
                currentTalker = lastFloor.currentTalker,
                queue = lastFloor.queue.filter { it != me },
                lastUpdate = now
            )
        }
        broadcastFloor(lastFloor)
        updatePresence(micStatus = false, floorStatus = "idle")
        emitPtt(PttState.IDLE)
    }

    private fun broadcastFloor(state: FloorState) {
        val payload = JSONObject()
            .put("jenis", "floorState")
            .put("state", JSONObject()
                .put("currentTalker", state.currentTalker ?: JSONObject.NULL)
                .put("queue", JSONArray().apply { state.queue.forEach { put(it) } })
                .put("lastUpdate", state.lastUpdate))
        runCatching { socketClient.emitFloorEvent(payload) }
            .onFailure { e ->
                listener?.onError(CommunicationError.Socket(e.message ?: "Gagal mengirim floor."))
            }
    }

    // ---- A15SocketCallback ----

    override fun onSocketState(state: A15SocketState) {
        emitConnection(
            when (state) {
                A15SocketState.CONNECTING -> ConnectionState.CONNECTING
                A15SocketState.CONNECTED, A15SocketState.RECONNECTING -> ConnectionState.ONLINE
                A15SocketState.DISCONNECTED -> ConnectionState.DISCONNECTED
                A15SocketState.ERROR -> ConnectionState.ERROR
            }
        )
    }

    override fun onAuthReady(user: JSONObject) {
        currentUser = parseUser(user)
        listener?.onAuthReady(currentUser!!)
    }

    override fun onServerReady(version: String) {
        listener?.onServerReady(version)
    }

    override fun onRoomJoined(users: JSONArray, self: JSONObject) {
        roomJoined = true
        currentUser = currentUser ?: parseUser(self)
        listener?.onRoomJoined(parseUsers(users))
    }

    override fun onRoomUsers(users: JSONArray) {
        listener?.onRoomUsers(parseUsers(users))
    }

    override fun onPresenceUpdate(sessions: JSONObject) {
        listener?.onPresenceUpdated(parsePresenceSessions(sessions))
    }

    override fun onFloorEvent(payload: JSONObject) {
        val floor = FloorParser.parseFloorState(payload.toMap())
            ?: return
        lastFloor = floor
        listener?.onFloorEvent(lastFloor)
    }

    override fun onRoomError(message: String) {
        roomJoined = false
        listener?.onError(CommunicationError.Room(message))
    }

    override fun onAdminKick() {
        roomJoined = false
        emitConnection(ConnectionState.DISCONNECTED)
        listener?.onError(CommunicationError.Room("Anda telah di-kick oleh admin."))
    }

    // ---- helpers ----

    private fun requirePeerId(): String {
        val stored = prefs.getString(KEY_PEER_ID, null)
        if (!stored.isNullOrBlank()) return stored
        val generated = "android-" + UUID.randomUUID().toString()
        prefs.edit().putString(KEY_PEER_ID, generated).apply()
        return generated
    }

    private fun parseUser(user: JSONObject): AuthUser =
        AuthUser(
            id = if (user.has("id") && !user.isNull("id")) user.optLong("id") else null,
            nama = user.optString("nama", user.optString("username", "")),
            role = user.optString("role", "user"),
            status = user.optString("status", "aktif"),
            banned = user.optBoolean("banned", false),
            muted = user.optBoolean("muted", false)
        )

    private fun parseUsers(users: JSONArray): List<PresenceInfo> =
        List(users.length()) { i ->
            val u = users.optJSONObject(i) ?: return@List PresenceInfo(
                username = "", group = null, channel = null,
                peerId = null, micStatus = false, floorStatus = "idle"
            )
            PresenceInfo(
                username = u.optString("nama", ""),
                group = nullableString(u, "group"),
                channel = nullableString(u, "channel"),
                peerId = nullableString(u, "peerId"),
                micStatus = u.optBoolean("micStatus", false),
                floorStatus = u.optString("floorStatus", "idle")
            )
        }

    private fun parsePresenceSessions(sessions: JSONObject): List<PresenceInfo> = run {
        val names = sessions.keys()
        buildList {
            while (names.hasNext()) {
                val u = sessions.optJSONObject(names.next()) ?: continue
                add(PresenceInfo(
                    username = u.optString("nama", ""),
                    group = nullableString(u, "group"),
                    channel = nullableString(u, "channel"),
                    peerId = nullableString(u, "peerId"),
                    micStatus = u.optBoolean("micStatus", false),
                    floorStatus = u.optString("floorStatus", "idle")
                ))
            }
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> = toFlatMap()

    private fun nullableString(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).ifBlank { null }

    private fun emitConnection(state: ConnectionState) {
        listener?.onConnectionState(state)
    }

    private fun emitPtt(state: PttState) {
        // PTT state is surfaced via CommunicationListener-derived flow; kept for future UI.
    }

    fun shutdown() {
        scope.cancel()
        socketClient.disconnect()
        mediaManager?.shutdown()
        mediaManager = null
        mediaSocket?.disconnect()
        mediaSocket = null
    }

    private fun A15SocketClient.connectIfNeeded(tokenProvider: () -> String) {
        if (!isConnected) connect(tokenProvider())
    }

    companion object {
        private const val KEY_PEER_ID = "android_peer_id"
    }
}