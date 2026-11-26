package com.openvcam.core

import com.openvcam.R
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder

class CoreKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(1001, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, Notifications.CHANNEL_CORE)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(getString(R.string.notification_core_title))
            .setContentText(getString(R.string.notification_core_text))
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
