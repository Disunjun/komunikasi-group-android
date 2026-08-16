package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.CommunicationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A15RestClientTest {

    private val client = A15RestClient()

    @Test
    fun `maps 401 to Authentication error`() {
        val e = client.classifyResponse(401, ok = false, message = "Token tidak valid atau sudah kedaluwarsa.")
        assertTrue(e is CommunicationError.Authentication)
        assertEquals("Token tidak valid atau sudah kedaluwarsa.", e!!.message)
    }

    @Test
    fun `maps 403 to Authentication error with default message`() {
        val e = client.classifyResponse(403, ok = false, message = "")
        assertTrue(e is CommunicationError.Authentication)
        assertEquals("Akun tidak diizinkan masuk.", e!!.message)
    }

    @Test
    fun `maps 502 to Unknown error and keeps server message`() {
        val e = client.classifyResponse(502, ok = false, message = "Gagal memperoleh TURN credentials dari Cloudflare.")
        assertTrue(e is CommunicationError.Unknown)
        assertEquals("Gagal memperoleh TURN credentials dari Cloudflare.", e!!.message)
    }

    @Test
    fun `maps non-2xx without message to Unknown with generic fallback`() {
        val e = client.classifyResponse(500, ok = false, message = null)
        assertTrue(e is CommunicationError.Unknown)
        assertEquals("Backend HTTP 500", e!!.message)
    }

    @Test
    fun `maps ok false on 2xx to Unknown`() {
        val e = client.classifyResponse(200, ok = false, message = "Cloudflare TURN belum dikonfigurasi di server.")
        assertTrue(e is CommunicationError.Unknown)
        assertEquals("Cloudflare TURN belum dikonfigurasi di server.", e!!.message)
    }

    @Test
    fun `returns null on successful classification`() {
        val e = client.classifyResponse(200, ok = true, message = null)
        assertNull(e)
    }
}