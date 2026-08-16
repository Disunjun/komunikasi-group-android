package com.disunjun.komunikasigroup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.disunjun.komunikasigroup.communication.CommunicationForegroundService
import com.disunjun.komunikasigroup.domain.ConnectionState
import com.disunjun.komunikasigroup.domain.CommunicationState
import com.disunjun.komunikasigroup.domain.PttState

class MainActivity : ComponentActivity() {
    private val requestCode = 100
    private var state = CommunicationState()

    private lateinit var status: TextView
    private lateinit var onlineButton: Button
    private lateinit var pttButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()
        renderUi()
    }

    private fun renderUi() {
        status = TextView(this).apply { textSize = 18f; setPadding(32, 48, 32, 32) }
        onlineButton = Button(this).apply { text = "GO ONLINE" }
        pttButton = Button(this).apply { text = "PTT"; isEnabled = false }

        onlineButton.setOnClickListener {
            state = if (state.connection == ConnectionState.ONLINE) {
                stopCommunicationService()
                state.copy(connection = ConnectionState.OFFLINE, ptt = PttState.IDLE)
            } else {
                startCommunicationService()
                state.copy(connection = ConnectionState.ONLINE)
            }
            updateView()
        }

        pttButton.setOnClickListener {
            state = state.copy(
                ptt = if (state.ptt == PttState.TRANSMITTING) PttState.IDLE else PttState.TRANSMITTING
            )
            updateView()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply { text = "Komunikasi Group — Android V1 PoC-1"; textSize = 22f })
            addView(status)
            addView(onlineButton)
            addView(pttButton)
        }
        setContentView(root)
        updateView()
    }

    private fun updateView() {
        status.text = "Connection: ${state.connection}\nChannel: ${state.channelId}\nPTT: ${state.ptt}\nRecording: ${state.recording}"
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
