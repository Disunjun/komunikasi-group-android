package com.disunjun.komunikasigroup.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSignalCodecTest {

    private val candidate = CandidateWire(
        sdpMid = "0",
        sdpMLineIndex = 0,
        candidate = "candidate:1 1 UDP 2122260223 10.0.2.2 54321 typ host"
    )

    @Test
    fun `offer serialization round-trips through parseInbound`() {
        val built = MediaSignalCodec.buildOffer("sess-1", "peer-b", "v=0 offer")

        assertEquals("sess-1", built["sessionId"])
        assertEquals("peer-b", built["toPeerId"])
        assertEquals("v=0 offer", built["sdp"])

        val relayed: Map<String, Any?> = built + mapOf(
            "type" to "offer",
            "fromPeerId" to "peer-a",
            "group" to "div-a",
            "channel" to "ch-1"
        )

        val parsed = MediaSignalCodec.parseInbound(relayed)
        assertTrue(parsed is MediaSignalParseResult.Offer)
        parsed as MediaSignalParseResult.Offer
        assertEquals("sess-1", parsed.sessionId)
        assertEquals("peer-a", parsed.fromPeerId)
        assertEquals("v=0 offer", parsed.sdp)
    }

    @Test
    fun `answer serialization round-trips through parseInbound`() {
        val built = MediaSignalCodec.buildAnswer("sess-1", "peer-a", "v=0 answer")
        assertEquals("sess-1", built["sessionId"])
        assertEquals("peer-a", built["toPeerId"])

        val relayed: Map<String, Any?> = built + mapOf(
            "type" to "answer",
            "fromPeerId" to "peer-b",
            "group" to "div-a",
            "channel" to "ch-1"
        )
        val parsed = MediaSignalCodec.parseInbound(relayed)
        assertTrue(parsed is MediaSignalParseResult.Answer)
        parsed as MediaSignalParseResult.Answer
        assertEquals("sess-1", parsed.sessionId)
        assertEquals("peer-b", parsed.fromPeerId)
        assertEquals("v=0 answer", parsed.sdp)
    }

    @Test
    fun `ice candidate serialization survives the single-string relay field`() {
        val built = MediaSignalCodec.buildIce("sess-1", "peer-b", candidate)
        assertEquals("sess-1", built["sessionId"])
        val wire = built["candidate"] as String

        val decoded = MediaSignalCodec.decodeCandidate(wire)
        assertTrue(decoded != null)
        assertEquals("0", decoded!!.sdpMid)
        assertEquals(0, decoded.sdpMLineIndex)
        assertEquals(candidate.candidate, decoded.candidate)

        val relayed: Map<String, Any?> = mapOf(
            "sessionId" to "sess-1",
            "fromPeerId" to "peer-a",
            "candidate" to wire
        )
        val parsed = MediaSignalCodec.parseInbound(relayed)
        assertTrue(parsed is MediaSignalParseResult.IceCandidate)
        parsed as MediaSignalParseResult.IceCandidate
        assertEquals("peer-a", parsed.fromPeerId)
        assertEquals(candidate.candidate, parsed.candidate.candidate)
    }

    @Test
    fun `leave serialization matches relay broadcast contract`() {
        val built = MediaSignalCodec.buildLeave("sess-1")
        assertEquals("sess-1", built["sessionId"])

        val relayed: Map<String, Any?> = mapOf(
            "sessionId" to "sess-1",
            "peerId" to "peer-a",
            "group" to "div-a",
            "channel" to "ch-1"
        )
        val parsed = MediaSignalCodec.parseInbound(relayed)
        assertTrue(parsed is MediaSignalParseResult.Leave)
        parsed as MediaSignalParseResult.Leave
        assertEquals("sess-1", parsed.sessionId)
        assertEquals("peer-a", parsed.peerId)
        assertEquals("ch-1", parsed.channel)
    }

    @Test
    fun `error frame parses to domain error with code mapping`() {
        val relayed: Map<String, Any?> = mapOf(
            "code" to "PEER_ID_MISMATCH",
            "message" to "fromPeerId does not match authenticated session",
            "sessionId" to "sess-1"
        )
        val parsed = MediaSignalCodec.parseInbound(relayed)
        assertTrue(parsed is MediaSignalParseResult.Error)
        parsed as MediaSignalParseResult.Error
        assertEquals(MediaErrorCode.PEER_ID_MISMATCH, parsed.code)
        assertEquals("sess-1", parsed.sessionId)
    }

    @Test
    fun `unknown error code maps to null instead of throwing`() {
        val relayed: Map<String, Any?> = mapOf("code" to "SHOPPING_LIST", "message" to "?", "sessionId" to "s1")
        val parsed = MediaSignalCodec.parseInbound(relayed)
        assertTrue(parsed is MediaSignalParseResult.Error)
        parsed as MediaSignalParseResult.Error
        assertNull(parsed.code)
    }

    @Test
    fun `sessionId is preserved consistently across build and parse`() {
        val sessionId = "7f8ec0f2-0000-4000-8000-000000000001"
        val built = MediaSignalCodec.buildOffer(sessionId, "peer-b", "sdp")
        assertEquals(sessionId, built["sessionId"])

        val relayed = built + mapOf("type" to "offer", "fromPeerId" to "peer-a", "group" to "g", "channel" to "c")
        val parsed = MediaSignalCodec.parseInbound(relayed) as MediaSignalParseResult.Offer
        assertEquals(sessionId, parsed.sessionId)

        val builtLeave = MediaSignalCodec.buildLeave(sessionId)
        assertEquals(sessionId, builtLeave["sessionId"])
    }

    @Test
    fun `malformed offer missing sdp still parses shape but empty sdp`() {
        val relayed: Map<String, Any?> = mapOf(
            "type" to "offer",
            "sessionId" to "s1",
            "fromPeerId" to "peer-a",
            "sdp" to ""
        )
        val parsed = MediaSignalCodec.parseInbound(relayed) as MediaSignalParseResult.Offer
        assertEquals("", parsed.sdp)
    }

    @Test
    fun `malformed candidate payload is rejected`() {
        val built = MediaSignalCodec.buildIce("s1", "peer-b", candidate)
        val wire = built["candidate"] as String

        assertNull(MediaSignalCodec.decodeCandidate("garbage-not-json"))
        assertNull(MediaSignalCodec.decodeCandidate(""))
        assertNull(MediaSignalCodec.decodeCandidate("{\"sdpMid\":null,\"sdpMLineIndex\":\"x\",\"sdp\":\"c\"}"))

        val badRelayed: Map<String, Any?> = mapOf(
            "sessionId" to "s1",
            "fromPeerId" to "peer-a",
            "candidate" to "not-embedded"
        )
        assertNull(MediaSignalCodec.parseInbound(badRelayed))
    }

    @Test
    fun `malformed leave without peer id is rejected`() {
        val relayed: Map<String, Any?> = mapOf("sessionId" to "s1", "peerId" to "")
        assertNull(MediaSignalCodec.parseInbound(relayed))
    }

    @Test
    fun `payload with no recognizable media signature is rejected`() {
        val relayed: Map<String, Any?> = mapOf("foo" to "bar")
        assertNull(MediaSignalCodec.parseInbound(relayed))
    }

    @Test
    fun `candidate wire handles escaping of quotes and backslashes`() {
        val tricky = CandidateWire(
            sdpMid = "a\"b",
            sdpMLineIndex = 1,
            candidate = "candidate:\\\\x \"quoted\" remain"
        )
        val wire = MediaSignalCodec.encodeCandidate(tricky)
        val decoded = MediaSignalCodec.decodeCandidate(wire)
        assertTrue(decoded != null)
        assertEquals("a\"b", decoded!!.sdpMid)
        assertEquals("candidate:\\\\x \"quoted\" remain", decoded.candidate)
    }
}