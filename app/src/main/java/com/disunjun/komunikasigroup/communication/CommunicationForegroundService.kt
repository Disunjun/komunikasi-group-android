package com.disunjun.komunikasigroup.communication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.disunjun.komunikasigroup.R

class CommunicationForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.disunjun.komunikasigroup.START"
        const val ACTION_STOP = "com.disunjun.komunikasigroup.STOP"
        private const val CHANNEL_ID = "communication"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopForeground(STOP_FOREGROUND_REMOVE).also { stopSelf() }
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
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Communication", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
