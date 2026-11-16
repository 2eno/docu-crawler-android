package dev.docucrawler.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class DocuCrawlerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Crawl-Fortschritt", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Laufender Crawl-Vorgang"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Crawl abgeschlossen", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "crawl_progress"
        const val CHANNEL_DONE = "crawl_done"
    }
}
