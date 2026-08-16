package com.disunjun.komunikasigroup.communication

import android.content.Context
import com.disunjun.komunikasigroup.domain.CommunicationPort
import com.disunjun.komunikasigroup.domain.TurnCredentialProvider

/**
 * Single shared instance of the active communication port.
 * Keeps networking ownership in the adapter while the ForegroundService
 * keeps owning the long-lived lifecycle.
 */
object CommunicationRuntime {
    @Volatile
    private var adapter: A15CommunicationAdapter? = null

    @Volatile
    private var turnCredentialProvider: TurnCredentialProvider? = null

    fun get(context: Context): A15CommunicationAdapter =
        adapter ?: synchronized(this) {
            adapter ?: A15CommunicationAdapter(context.applicationContext).also {
                adapter = it
            }
        }

    fun port(context: Context): CommunicationPort = get(context)

    /** F3A: real A1.5 TURN credential provider (in-memory credentials only). */
    fun turnCredentials(context: Context): TurnCredentialProvider =
        turnCredentialProvider ?: synchronized(this) {
            turnCredentialProvider ?: A15TurnCredentialProvider(
                tokenProvider = { TokenStore(context.applicationContext).getToken() }
            ).also { turnCredentialProvider = it }
        }

    fun release() {
        synchronized(this) {
            adapter?.shutdown()
            adapter = null
            turnCredentialProvider = null
        }
    }
}