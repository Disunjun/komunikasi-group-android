package com.disunjun.komunikasigroup.communication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.disunjun.komunikasigroup.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the long-lived Android communication lifecycle only.
 * All networking/business logic stays in the communication adapter(s);
 * this service never touches REST or Socket.IO directly.
 */
class CommunicationForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.disunjun.komunikasigroup.START"
        const val ACTION_STOP = "com.disunjun.komunikasigroup.STOP"
        private const val CHANNEL_ID = "communication"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCommunication()
            ACTION_START -> startCommunication()
        }
        return START_STICKY
    }

    private fun startCommunication() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Komunikasi Group")
            .setContentText("Android V1 communication service aktif")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val port = CommunicationRuntime.port(this)
        serviceScope.launch {
            port.connect("CH-01")
        }
    }

    private fun stopCommunication() {
        val port = CommunicationRuntime.port(this)
        serviceScope.launch {
            port.disconnect()
        }
        stopForeground(STOP_FOREGROUND_REMOVE).also { stopSelf() }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Communication", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}