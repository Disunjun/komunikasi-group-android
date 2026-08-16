package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.domain.AuthUser
import com.disunjun.komunikasigroup.domain.ChannelTarget
import com.disunjun.komunikasigroup.domain.CommunicationError
import com.disunjun.komunikasigroup.domain.CommunicationListener
import com.disunjun.komunikasigroup.domain.CommunicationPort

/**
 * PoC transport adapter. It deliberately contains no backend protocol knowledge.
 * Real transport (Web V2 / Backend A1.5 contract) plugs in behind CommunicationPort.
 */
class PoCCommunicationAdapter : CommunicationPort {
    private var connected = false
    private var listener: CommunicationListener? = null

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

    // ---- A1.5 control-plane: not implemented in PoC ----
    override suspend fun login(nama: String, sandi: String): Result<AuthUser> =
        Result.failure(CommunicationError.Socket("PoC adapter tidak terhubung ke backend A1.5."))

    override suspend fun restoreSession(): Result<AuthUser> =
        Result.failure(CommunicationError.Authentication("Belum ada sesi pada PoC adapter."))

    override suspend fun logout(): Result<Unit> {
        disconnect()
        return Result.success(Unit)
    }

    override suspend fun joinRoom(target: ChannelTarget): Result<Unit> =
        connect(target.channel)

    override fun updatePresence(micStatus: Boolean, floorStatus: String) = Unit

    override fun setListener(listener: CommunicationListener?) {
        this.listener = listener
    }
}