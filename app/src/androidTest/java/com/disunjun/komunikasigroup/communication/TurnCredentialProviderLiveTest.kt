package com.disunjun.komunikasigroup.communication

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.disunjun.komunikasigroup.domain.TurnCredentialProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live F3A verification against the real A1.5 backend (GET /api/turn-credentials).
 * Confirms a valid ICE server configuration is produced through the actual
 * provider implementation. HTTP credentials are asserted but never printed.
 */
@RunWith(AndroidJUnit4::class)
class TurnCredentialProviderLiveTest {

    @Test
    fun fetchRealTurnCredentialsProducesValidIceConfig() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider: TurnCredentialProvider = CommunicationRuntime.turnCredentials(context)

        val result = provider.getTurnCredentials()

        assertTrue("expected success, got $result", result.isSuccess)
        val creds = result.getOrThrow()

        assertNotNull(creds.iceServers)
        assertTrue("iceServers must not be empty", creds.iceServers.isNotEmpty())

        val urls = creds.iceServers.flatMap { it.urls }
        assertTrue("must contain turn/turns:", urls.any { it.startsWith("turn") || it.startsWith("turns") })

        val turnServer = creds.iceServers.first { it.urls.any { u -> u.startsWith("turn") } }
        assertNotNull("video TURN server must carry a username", turnServer.username)
        assertNotNull("TURN server must carry a credential", turnServer.credential)
        assertEquals("username/credential must be non-empty", true,
            !turnServer.username!!.isBlank() && !turnServer.credential!!.isBlank())
    }
}