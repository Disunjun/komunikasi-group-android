package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.TurnCredentialProvider
import com.disunjun.komunikasigroup.domain.TurnCredentials

/**
 * F3A: TURN credential provider backed by the A1.5 REST contract.
 *
 * Fetches short-lived Cloudflare ICE servers from GET /api/turn-credentials,
 * reusing the authenticated REST infrastructure. Credentials are returned in
 * memory only and are never logged, written to disk, or embedded at build time.
 */
class A15TurnCredentialProvider(
    private val restClient: A15RestClient = A15RestClient(),
    private val tokenProvider: () -> String? = { null }
) : TurnCredentialProvider {

    override fun getTurnCredentials(): Result<TurnCredentials> =
        restClient.turnCredentials(tokenProvider())
}