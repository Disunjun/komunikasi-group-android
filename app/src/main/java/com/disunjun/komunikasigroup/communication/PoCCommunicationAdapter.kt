package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.CommunicationPort
import kotlinx.coroutines.runBlocking

/**
 * PoC transport adapter. It deliberately contains no backend protocol knowledge.
 * Real transport (Web V2 / Backend A1.5 contract) plugs in behind CommunicationPort.
 */
class PoCCommunicationAdapter : CommunicationPort {
    private var connected = false

    override suspend fun connect(channelId: String): Result<Unit> {
        connected = true
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connected = false
    }

    override fun startPtt() {
        check(connected) { "Communication is offline" }
    }

    override fun stopPtt() = Unit
}
