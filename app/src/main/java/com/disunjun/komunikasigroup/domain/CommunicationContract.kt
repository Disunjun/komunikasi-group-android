package com.disunjun.komunikasigroup.domain

/** Stable domain language for Android V1. Transport is intentionally behind this contract. */
enum class ConnectionState { OFFLINE, CONNECTING, ONLINE, ERROR }
enum class PttState { IDLE, TRANSMITTING }
enum class RecordingState { IDLE, RECORDING, STOPPED, ERROR }

data class CommunicationState(
    val connection: ConnectionState = ConnectionState.OFFLINE,
    val ptt: PttState = PttState.IDLE,
    val recording: RecordingState = RecordingState.IDLE,
    val channelId: String = "CH-01",
    val error: String? = null
)

interface CommunicationPort {
    suspend fun connect(channelId: String): Result<Unit>
    suspend fun disconnect()
    fun startPtt()
    fun stopPtt()
}

/** Recording Contract V3 boundary: UI/service never depends on a concrete recorder. */
interface RecordingPort {
    fun start(): Result<Unit>
    fun stop(): Result<Unit>
}
