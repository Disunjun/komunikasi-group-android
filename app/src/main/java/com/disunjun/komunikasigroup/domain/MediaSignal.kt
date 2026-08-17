package com.disunjun.komunikasigroup.domain

/**
 * ICE candidate fragment exchanged over the v3 media relay.
 *
 * The v3 contract relays one opaque `candidate` string per `media:ice-candidate`
 * event. A WebRTC IceCandidate needs (sdpMid, sdpMLineIndex, sdp), so the
 * transport packs all three into the single string carried by the relay.
 */
data class CandidateWire(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String
)

/** Error codes officially defined by the v3 media relay. */
enum class MediaErrorCode(val code: String) {
    AUTH_REQUIRED("AUTH_REQUIRED"),
    ROOM_REQUIRED("ROOM_REQUIRED"),
    TARGET_NOT_FOUND("TARGET_NOT_FOUND"),
    TARGET_NOT_SAME_ROOM("TARGET_NOT_SAME_ROOM"),
    INVALID_PAYLOAD("INVALID_PAYLOAD"),
    INVALID_SESSION("INVALID_SESSION"),
    PEER_ID_MISMATCH("PEER_ID_MISMATCH");

    companion object {
        fun from(code: String?): MediaErrorCode? =
            entries.firstOrNull { it.code == code }
    }
}

/** Result of validating an inbound media relay payload. */
sealed class MediaSignalParseResult {
    data class Offer(
        val sessionId: String,
        val fromPeerId: String,
        val group: String,
        val channel: String,
        val sdp: String
    ) : MediaSignalParseResult()

    data class Answer(
        val sessionId: String,
        val fromPeerId: String,
        val group: String,
        val channel: String,
        val sdp: String
    ) : MediaSignalParseResult()

    data class IceCandidate(
        val sessionId: String,
        val fromPeerId: String,
        val candidate: CandidateWire
    ) : MediaSignalParseResult()

    data class Leave(
        val sessionId: String,
        val peerId: String,
        val group: String,
        val channel: String
    ) : MediaSignalParseResult()

    data class Error(
        val code: MediaErrorCode?,
        val message: String,
        val sessionId: String?
    ) : MediaSignalParseResult()
}

/**
 * Pure codec for the v3 media signaling contract.
 *
 * Free of Android/HTTP/WebRTC types so the wire contract is unit-testable on the
 * JVM. Transport adapters map raw JSON into Map<String, Any?> and delegate here.
 *
 * Wire shapes (schema freeze — matches server/src/media-signaling.js):
 *   outbound offer/answer : {type?, sessionId, toPeerId, sdp}
 *   outbound ice          : {sessionId, toPeerId, candidate}
 *   outbound leave        : {sessionId}
 *   inbound offer/answer  : {type, sessionId, fromPeerId, toPeerId, group, channel, sdp}
 *   inbound ice           : {sessionId, fromPeerId, toPeerId, group, channel, candidate}
 *   inbound leave         : {sessionId, peerId, group, channel}
 *   inbound error         : {code, message, sessionId}
 */
object MediaSignalCodec {

    fun buildOffer(sessionId: String, toPeerId: String, sdp: String): Map<String, Any?> =
        mapOf("sessionId" to sessionId, "toPeerId" to toPeerId, "sdp" to sdp)

    fun buildAnswer(sessionId: String, toPeerId: String, sdp: String): Map<String, Any?> =
        mapOf("sessionId" to sessionId, "toPeerId" to toPeerId, "sdp" to sdp)

    fun buildIce(sessionId: String, toPeerId: String, candidate: CandidateWire): Map<String, Any?> =
        mapOf("sessionId" to sessionId, "toPeerId" to toPeerId, "candidate" to encodeCandidate(candidate))

    fun buildLeave(sessionId: String): Map<String, Any?> =
        mapOf("sessionId" to sessionId)

    /**
     * Embed a WebRTC IceCandidate into the single string the relay passes through.
     * Format: a JSON object string {"sdpMid":..., "sdpMLineIndex":..., "sdp":...}.
     */
    fun encodeCandidate(candidate: CandidateWire): String {
        val b = StringBuilder("{\"sdpMid\":")
        b.append(if (candidate.sdpMid == null) "null" else "\"" + escape(candidate.sdpMid) + "\"")
        b.append(",\"sdpMLineIndex\":").append(candidate.sdpMLineIndex)
        b.append(",\"sdp\":\"").append(escape(candidate.candidate)).append("\"")
        b.append("}")
        return b.toString()
    }

    /** Inverse of [encodeCandidate]. Returns null on malformed input. */
    fun decodeCandidate(raw: String): CandidateWire? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        val sdpMid = regexField(trimmed, "sdpMid")
        val mline = regexField(trimmed, "sdpMLineIndex")?.toIntOrNull() ?: return null
        val sdp = regexField(trimmed, "sdp") ?: return null
        return CandidateWire(
            sdpMid = unescape(sdpMid),
            sdpMLineIndex = mline,
            candidate = unescape(sdp) ?: sdp
        )
    }

    /**
     * Parse an inbound relayed media frame. Returns null when the payload is not
     * a recognisable media signaling frame (identity/type not resolvable).
     */
    fun parseInbound(payload: Map<String, Any?>): MediaSignalParseResult? {
        val type = payload["type"] as? String
        val sessionId = payload["sessionId"]?.toString().orEmpty()
        val fromPeerId = payload["fromPeerId"]?.toString().orEmpty()
        val group = payload["group"]?.toString().orEmpty()
        val channel = payload["channel"]?.toString().orEmpty()

        if (type != null && type.isNotBlank()) {
            val sdp = payload["sdp"]?.toString().orEmpty()
            return when (type) {
                "offer" -> MediaSignalParseResult.Offer(
                    sessionId = sessionId,
                    fromPeerId = fromPeerId,
                    group = group,
                    channel = channel,
                    sdp = sdp
                )
                "answer" -> MediaSignalParseResult.Answer(
                    sessionId = sessionId,
                    fromPeerId = fromPeerId,
                    group = group,
                    channel = channel,
                    sdp = sdp
                )
                else -> null
            }
        }

        if (payload["candidate"] != null) {
            val raw = payload["candidate"]?.toString().orEmpty()
            val candidate = decodeCandidate(raw)
            if (candidate == null || sessionId.isBlank() || fromPeerId.isBlank()) return null
            return MediaSignalParseResult.IceCandidate(
                sessionId = sessionId,
                fromPeerId = fromPeerId,
                candidate = candidate
            )
        }

        if (payload["peerId"] != null) {
            val peerId = payload["peerId"]?.toString().orEmpty()
            if (sessionId.isBlank() || peerId.isBlank()) return null
            return MediaSignalParseResult.Leave(
                sessionId = sessionId,
                peerId = peerId,
                group = group,
                channel = channel
            )
        }

        if (payload["code"] != null) {
            val code = MediaErrorCode.from(payload["code"]?.toString())
            val message = payload["message"]?.toString().orEmpty()
            return MediaSignalParseResult.Error(
                code = code,
                message = message,
                sessionId = payload["sessionId"]?.toString()
            )
        }

        return null
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun regexField(json: String, key: String): String? {
        val match = Regex("\"${key}\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|null|[0-9]+)").find(json)
            ?: return null
        val raw = match.groupValues[1]
        return if (raw == "null") {
            null
        } else if (raw.startsWith("\"")) {
            raw.drop(1).dropLast(1)
        } else {
            raw
        }
    }

    private fun unescape(s: String?): String? {
        if (s == null) return null
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }
}