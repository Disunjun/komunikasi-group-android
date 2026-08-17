package com.disunjun.komunikasigroup.domain

/** Lifecycle of a single WebRTC media session on the V1/F3B-A signaling flow. */
enum class MediaSessionState { NEW, SIGNALING, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/**
 * A single WebRTC session tracked by the media signaling layer.
 *
 * - [sessionId] is unique per WebRTC session and is the correlation key shared
 *   with the v3 media relay (`payload.sessionId`).
 * - [localPeerId] is the stable Android peer identity (`android-<uuid>`).
 * - [remotePeerId] is the remote participant's routing identity (their v3
 *   sessionId) once the peer is known. The two identities are intentionally
 *   kept separate.
 */
data class MediaSession(
    val sessionId: String,
    val localPeerId: String,
    val remotePeerId: String?,
    val group: String,
    val channel: String,
    val state: MediaSessionState
) {
    fun copyState(state: MediaSessionState) = copy(state = state)
}