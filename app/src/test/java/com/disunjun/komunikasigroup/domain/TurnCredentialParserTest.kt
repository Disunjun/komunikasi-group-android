package com.disunjun.komunikasigroup.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnCredentialParserTest {

    @Test
    fun `parses successful TURN response`() {
        val payload = mapOf(
            "ok" to true,
            "ttl" to 86400,
            "iceServers" to listOf(
                mapOf("urls" to listOf("stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53")),
                mapOf(
                    "urls" to listOf(
                        "turn:turn.cloudflare.com:3478?transport=udp",
                        "turns:turn.cloudflare.com:5349?transport=tcp"
                    ),
                    "username" to "g03d...50b",
                    "credential" to "b9b4...8e4"
                )
            )
        )

        val result = TurnCredentialParser.parse(payload)

        assertTrue(result.isSuccess)
        val creds = result.getOrThrow()
        assertEquals(86400L, creds.ttl)
        assertEquals(2, creds.iceServers.size)
        assertEquals(listOf("stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"), creds.iceServers[0].urls)
        assertEquals("g03d...50b", creds.iceServers[1].username)
        assertEquals("b9b4...8e4", creds.iceServers[1].credential)
    }

    @Test
    fun `returns failure when iceServers missing`() {
        val payload = mapOf("ok" to true, "ttl" to 86400)

        val result = TurnCredentialParser.parse(payload)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CommunicationError.Turn)
    }

    @Test
    fun `returns failure when iceServers not a list`() {
        val payload = mapOf("ok" to true, "iceServers" to "not-a-list")

        val result = TurnCredentialParser.parse(payload)

        assertTrue(result.isFailure)
    }

    @Test
    fun `returns failure when no valid ice servers`() {
        val payload = mapOf(
            "ok" to true,
            "iceServers" to listOf(
                mapOf("urls" to emptyList<String>()),
                mapOf("urls" to listOf(""))
            )
        )

        val result = TurnCredentialParser.parse(payload)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CommunicationError.Turn)
    }

    @Test
    fun `skips malformed ice server entries`() {
        val payload = mapOf(
            "ok" to true,
            "iceServers" to listOf(
                "not-a-map",
                42
            )
        )

        val result = TurnCredentialParser.parse(payload)

        assertTrue(result.isFailure)
    }

    @Test
    fun `parses single-string urls form`() {
        val payload = mapOf(
            "ok" to true,
            "ttl" to 3600,
            "iceServers" to listOf(
                mapOf("urls" to "stun:stun.cloudflare.com:3478"),
                mapOf(
                    "urls" to "turn:turn.cloudflare.com:3478?transport=udp",
                    "username" to "u1",
                    "credential" to "c1"
                )
            )
        )

        val result = TurnCredentialParser.parse(payload)

        assertTrue(result.isSuccess)
        val creds = result.getOrThrow()
        assertEquals(2, creds.iceServers.size)
        assertEquals("turn:turn.cloudflare.com:3478?transport=udp", creds.iceServers[1].urls.first())
        assertEquals("u1", creds.iceServers[1].username)
        assertEquals("c1", creds.iceServers[1].credential)
    }

    @Test
    fun `parses creds without username or credential fields`() {
        val payload = mapOf(
            "ok" to true,
            "iceServers" to listOf(
                mapOf("urls" to listOf("turn:turn.cloudflare.com:3478?transport=udp"))
            )
        )

        val result = TurnCredentialParser.parse(payload)

        assertTrue(result.isSuccess)
        val server = result.getOrThrow().iceServers.first()
        assertEquals("turn:turn.cloudflare.com:3478?transport=udp", server.urls.first())
        assertEquals(null, server.username)
        assertEquals(null, server.credential)
    }

    @Test
    fun `returns failure with server message when ok is false`() {
        val payload = mapOf(
            "ok" to false,
            "message" to "Cloudflare TURN belum dikonfigurasi di server."
        )

        val result = TurnCredentialParser.parse(payload)

        assertTrue(result.isFailure)
        val e = result.exceptionOrNull() as CommunicationError.Turn
        assertEquals("Cloudflare TURN belum dikonfigurasi di server.", e.message)
    }
}