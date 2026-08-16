package com.disunjun.komunikasigroup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
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
import com.disunjun.komunikasigroup.domain.PresenceInfo
import com.disunjun.komunikasigroup.domain.PttState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestCode = 100
    private var state = CommunicationState()

    private lateinit var status: TextView
    private lateinit var onlineButton: Button
    private lateinit var pttButton: Button

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = CommunicationRuntime.port(this)
        port.setListener(listener)
        requestRequiredPermissions()
        renderUi()
    }

    override fun onDestroy() {
        CommunicationRuntime.port(this).setListener(null)
        super.onDestroy()
    }

    private fun renderUi() {
        status = TextView(this).apply { textSize = 18f; setPadding(32, 48, 32, 32) }
        onlineButton = Button(this).apply { text = "GO ONLINE" }
        pttButton = Button(this).apply { text = "PTT"; isEnabled = false }

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply { text = "Komunikasi Group — Android V1 F2"; textSize = 22f })
            addView(status)
            addView(onlineButton)
            addView(pttButton)
        }
        setContentView(root)
        updateView()
    }

    private fun updateView() {
        val errorLine = state.error?.let { "\nError: $it" } ?: ""
        status.text = "Connection: ${state.connection}\nChannel: ${state.channelId}\nPTT: ${state.ptt}\nRecording: ${state.recording}$errorLine"
        onlineButton.text = if (state.connection == ConnectionState.ONLINE) "GO OFFLINE" else "GO ONLINE"
        pttButton.isEnabled = state.connection == ConnectionState.ONLINE
        pttButton.text = if (state.ptt == PttState.TRANSMITTING) "RELEASE PTT" else "PTT"
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