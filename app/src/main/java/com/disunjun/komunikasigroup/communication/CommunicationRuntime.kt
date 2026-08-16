package com.disunjun.komunikasigroup.communication

import android.content.Context
import com.disunjun.komunikasigroup.domain.CommunicationPort

/**
 * Single shared instance of the active communication port.
 * Keeps networking ownership in the adapter while the ForegroundService
 * keeps owning the long-lived lifecycle.
 */
object CommunicationRuntime {
    @Volatile
    private var adapter: A15CommunicationAdapter? = null

    fun get(context: Context): A15CommunicationAdapter =
        adapter ?: synchronized(this) {
            adapter ?: A15CommunicationAdapter(context.applicationContext).also {
                adapter = it
            }
        }

    fun port(context: Context): CommunicationPort = get(context)

    fun release() {
        synchronized(this) {
            adapter?.shutdown()
            adapter = null
        }
    }
}