package com.openvcam.core

import com.openvcam.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object Notifications {
    const val CHANNEL_CORE = "openvcam_core"
    const val CHANNEL_CONTROL = "openvcam_control"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val core = NotificationChannel(
            CHANNEL_CORE,
            context.getString(R.string.channel_core),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }

        val control = NotificationChannel(
            CHANNEL_CONTROL,
            context.getString(R.string.channel_control),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }

        manager.createNotificationChannel(core)
        manager.createNotificationChannel(control)
    }
}
