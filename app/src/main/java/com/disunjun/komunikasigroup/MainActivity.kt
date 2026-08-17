package com.disunjun.komunikasigroup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.disunjun.komunikasigroup.communication.CommunicationForegroundService
import com.disunjun.komunikasigroup.communication.CommunicationRuntime
import com.disunjun.komunikasigroup.domain.AuthUser
import com.disunjun.komunikasigroup.domain.CommunicationError
import com.disunjun.komunikasigroup.domain.CommunicationListener
import com.disunjun.komunikasigroup.domain.ConnectionState
import com.disunjun.komunikasigroup.domain.CommunicationState
import com.disunjun.komunikasigroup.domain.MediaCallSnapshot
import com.disunjun.komunikasigroup.domain.MediaCallStage
import com.disunjun.komunikasigroup.domain.MediaPeer
import com.disunjun.komunikasigroup.domain.MediaSignalingListener
import com.disunjun.komunikasigroup.domain.PresenceInfo
import com.disunjun.komunikasigroup.domain.PttState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestCode = 100
    private var state = CommunicationState()

    private lateinit var status: TextView
    private lateinit var mediaStatus: TextView
    private lateinit var onlineButton: Button
    private lateinit var pttButton: Button
    private lateinit var mediaCallButton: Button
    private lateinit var mediaLeaveButton: Button
    private lateinit var mediaLoginButton: Button
    private lateinit var mediaJoinButton: Button
    private lateinit var nameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var channelIdInput: EditText
    private var peersText: List<MediaPeer> = emptyList()
    private val defaultChannelId = "9e76f66a-5a5f-4221-a3f5-c5f7cd487bbd"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener = object : CommunicationListener {
        override fun onConnectionState(connection: ConnectionState) {
            mainHandler.post {
                state = state.copy(connection = connection, error = null)
                updateView()
            }
        }

        override fun onAuthReady(user: AuthUser) {
            mainHandler.post {
                state = state.copy(error = null)
                updateView()
            }
        }

        override fun onServerReady(version: String) {
            mainHandler.post { updateView() }
        }

        override fun onRoomJoined(members: List<PresenceInfo>) = Unit
        override fun onRoomUsers(members: List<PresenceInfo>) = Unit
        override fun onPresenceUpdated(sessions: List<PresenceInfo>) = Unit
        override fun onFloorEvent(state: com.disunjun.komunikasigroup.domain.FloorState) = Unit

        override fun onError(error: CommunicationError) {
            mainHandler.post {
                state = state.copy(error = error.message)
                updateView()
            }
        }
    }

    private val mediaListener = object : MediaSignalingListener {
        override fun onMediaCallSnapshot(snapshot: MediaCallSnapshot) {
            mainHandler.post {
                state = state.copy(error = snapshot.error)
                mediaStatus.text = "Media: ${snapshot.stage}"
                updateView()
            }
        }

        override fun onMediaPeers(peers: List<MediaPeer>) {
            mainHandler.post {
                peersText = peers
                updateView()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = CommunicationRuntime.port(this)
        port.setListener(listener)
        port.setMediaSignalingListener(mediaListener)
        requestRequiredPermissions()
        renderUi()
    }

    override fun onDestroy() {
        CommunicationRuntime.port(this).setListener(null)
        CommunicationRuntime.port(this).setMediaSignalingListener(null)
        super.onDestroy()
    }

    private fun renderUi() {
        status = TextView(this).apply { textSize = 18f; setPadding(32, 48, 32, 32) }
        mediaStatus = TextView(this).apply { textSize = 15f; setPadding(32, 8, 32, 8); text = "Media: OFFLINE" }
        onlineButton = Button(this).apply { text = "GO ONLINE" }
        pttButton = Button(this).apply { text = "PTT"; isEnabled = false }
        mediaCallButton = Button(this).apply { text = "START MEDIA CALL" }
        mediaLeaveButton = Button(this).apply { text = "LEAVE MEDIA CALL"; isEnabled = false }
        nameInput = EditText(this).apply { hint = "v3 username (ex: f3b_alice)" }
        passwordInput = EditText(this).apply {
            hint = "v3 password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        mediaLoginButton = Button(this).apply { text = "MEDIA LOGIN" }
        channelIdInput = EditText(this).apply { setText(defaultChannelId) }
        mediaJoinButton = Button(this).apply { text = "JOIN CHANNEL" }

        onlineButton.setOnClickListener {
            if (state.connection == ConnectionState.ONLINE) {
                state = state.copy(connection = ConnectionState.OFFLINE, ptt = PttState.IDLE)
                stopCommunicationService()
            } else {
                state = state.copy(connection = ConnectionState.CONNECTING)
                startCommunicationService()
            }
            updateView()
        }

        pttButton.setOnClickListener {
            val port = CommunicationRuntime.port(this)
            if (state.ptt == PttState.TRANSMITTING) {
                port.stopPtt()
                state = state.copy(ptt = PttState.IDLE)
            } else {
                port.startPtt()
                state = state.copy(ptt = PttState.TRANSMITTING)
            }
            updateView()
        }

        mediaCallButton.setOnClickListener {
            val port = CommunicationRuntime.port(this)
            val target = peersText.firstOrNull() ?: run {
                state = state.copy(error = "Tidak ada rekan di channel media.")
                updateView()
                return@setOnClickListener
            }
            val result = port.startMediaCall(target.sessionId)
            if (result.isFailure) {
                state = state.copy(error = result.exceptionOrNull()?.message)
                updateView()
            }
        }

        mediaLeaveButton.setOnClickListener {
            CommunicationRuntime.port(this).stopMediaCall()
            mediaLeaveButton.isEnabled = false
        }

        val scope = CoroutineScope(Dispatchers.IO)
        mediaLoginButton.setOnClickListener {
            val port = CommunicationRuntime.port(this)
            scope.launch {
                val result = port.mediaLogin(
                    nameInput.text.toString().trim(),
                    passwordInput.text.toString().trim()
                )
                mainHandler.post {
                    state = state.copy(
                        error = result.exceptionOrNull()?.message
                            ?: "Media login: OK"
                    )
                    updateView()
                }
            }
        }

        mediaJoinButton.setOnClickListener {
            val port = CommunicationRuntime.port(this)
            val channelId = channelIdInput.text.toString().trim().ifBlank { defaultChannelId }
            scope.launch {
                val result = port.mediaJoinChannel(channelId)
                mainHandler.post {
                    state = state.copy(error = result.exceptionOrNull()?.message)
                    updateView()
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply { text = "Komunikasi Group — Android V1 F3B-A"; textSize = 22f })
            addView(status)
            addView(mediaStatus)
            addView(onlineButton)
            addView(nameInput)
            addView(passwordInput)
            addView(mediaLoginButton)
            addView(channelIdInput)
            addView(mediaJoinButton)
            addView(mediaCallButton)
            addView(mediaLeaveButton)
            addView(pttButton)
        }
        setContentView(root)
        updateView()
    }

    private fun updateView() {
        val errorLine = state.error?.let { "\nError: $it" } ?: ""
        val peersLine = if (peersText.isEmpty()) {
            ""
        } else {
            "\nPeers: " + peersText.joinToString(", ") { it.name }
        }
        status.text = "Connection: ${state.connection}\nChannel: ${state.channelId}\nPTT: ${state.ptt}\nRecording: ${state.recording}$errorLine$peersLine"
        onlineButton.text = if (state.connection == ConnectionState.ONLINE) "GO OFFLINE" else "GO ONLINE"
        pttButton.isEnabled = state.connection == ConnectionState.ONLINE
        pttButton.text = if (state.ptt == PttState.TRANSMITTING) "RELEASE PTT" else "PTT"
        mediaCallButton.isEnabled = peersText.isNotEmpty()
        mediaLeaveButton.isEnabled = true
    }

    private fun startCommunicationService() {
        val intent = Intent(this, CommunicationForegroundService::class.java)
            .setAction(CommunicationForegroundService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopCommunicationService() {
        val intent = Intent(this, CommunicationForegroundService::class.java)
            .setAction(CommunicationForegroundService.ACTION_STOP)
        startService(intent)
    }

    private fun requestRequiredPermissions() {
        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) ActivityCompat.requestPermissions(this, permissions.toTypedArray(), requestCode)
    }
}