package com.ravi.askgalaxy

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Receives future notifications only after the user explicitly grants listener access. */
class PersonalContextNotificationListener : NotificationListenerService() {
    private lateinit var database: PersonalContextDatabase
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        database = PersonalContextDatabase(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!PersonalContextSettings.isEnabled(applicationContext)) return
        val draft = runCatching {
            NotificationContextParser.parse(applicationContext, sbn)
        }.getOrNull() ?: return
        executor.execute {
            runCatching { database.insert(draft) }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        if (::database.isInitialized) database.close()
        super.onDestroy()
    }
}
