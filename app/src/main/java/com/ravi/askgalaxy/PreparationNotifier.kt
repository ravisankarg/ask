package com.ravi.askgalaxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

object PreparationNotifier {
    const val CHANNEL_ID = "ask_galaxy_preparation"
    const val NOTIFICATION_ID = 4101

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Ask Galaxy preparation",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background model installation and gallery indexing"
                setShowBadge(false)
            },
        )
    }

    fun foregroundInfo(context: Context, snapshot: PreparationSnapshot): ForegroundInfo {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Ask Galaxy")
            .setContentText(snapshot.message)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, snapshot.percent, snapshot.total <= 0L)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
