package dev.docucrawler.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.docucrawler.android.DocuCrawlerApp
import dev.docucrawler.android.MainActivity
import dev.docucrawler.android.R
import dev.docucrawler.android.crawler.CrawlConfig
import dev.docucrawler.android.crawler.LogLevel
import dev.docucrawler.android.crawler.WebViewCookieProvider
import dev.docucrawler.android.crawler.WikiCrawler
import dev.docucrawler.android.data.CrawlStateRepository
import dev.docucrawler.android.data.CrawlStatus
import dev.docucrawler.android.data.SessionRepository
import dev.docucrawler.android.export.ProjectStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-Service, der den Crawl ausführt. Läuft weiter, wenn das Display aus ist oder die
 * App im Hintergrund liegt (Typ `dataSync` + Partial-WakeLock).
 */
class WikiCrawlerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var crawlJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.let { IntentCompat.getParcelableExtra(it, EXTRA_CONFIG, CrawlConfig::class.java) }
                if (config == null || crawlJob?.isActive == true) return START_NOT_STICKY
                goForeground(buildProgressNotification("Starte…", null, 0, 0))
                startCrawl(config)
            }
            ACTION_STOP -> {
                if (crawlJob?.isActive == true) {
                    CrawlStateRepository.update { it.copy(status = CrawlStatus.CANCELLING) }
                    CrawlStateRepository.log(LogLevel.WARN, "Abbruch angefordert…")
                    crawlJob?.cancel()
                } else {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startCrawl(config: CrawlConfig) {
        val outputDir = ProjectStorage.projectDir(this, config.projectName)
        val session = SessionRepository.session.value
        val userAgent = session?.userAgent ?: SessionRepository.defaultUserAgent(this)

        CrawlStateRepository.reset(config.projectName, config.startUrl)
        acquireWakeLock()

        val crawler = WikiCrawler(
            config = config,
            outputDir = outputDir,
            cookieProvider = WebViewCookieProvider(session?.cookieHeader?.ifBlank { null }, session?.host),
            httpAuth = session?.httpAuth,
            userAgent = userAgent,
            listener = object : WikiCrawler.Listener {
                override fun onProgress(progress: WikiCrawler.CrawlProgress) {
                    CrawlStateRepository.update {
                        it.copy(
                            pagesSaved = progress.pagesSaved,
                            queueSize = progress.queueSize,
                            visited = progress.visited,
                            errors = progress.errors,
                            skipped = progress.skipped,
                            currentUrl = progress.currentUrl ?: it.currentUrl,
                            currentDepth = progress.currentDepth,
                        )
                    }
                    maybeUpdateNotification(progress)
                }

                override fun onLog(level: LogLevel, message: String) {
                    CrawlStateRepository.log(level, message)
                }
            },
        )

        crawlJob = scope.launch {
            val finalStatus = try {
                crawler.run()
                CrawlStatus.FINISHED
            } catch (e: CancellationException) {
                CrawlStateRepository.log(LogLevel.WARN, "Crawl abgebrochen. Bereits gespeicherte Seiten bleiben erhalten.")
                CrawlStatus.CANCELLED
            } catch (e: Exception) {
                CrawlStateRepository.log(LogLevel.ERROR, "Crawl fehlgeschlagen: ${e.message}")
                CrawlStatus.FAILED
            }
            CrawlStateRepository.update {
                it.copy(status = finalStatus, finishedAt = System.currentTimeMillis(), currentUrl = null)
            }
            runCatching { WebViewCookieProvider(null, null).flush() }
            finish(finalStatus)
        }
    }

    private fun finish(status: CrawlStatus) {
        releaseWakeLock()
        val state = CrawlStateRepository.state.value
        val title = when (status) {
            CrawlStatus.FINISHED -> "Crawl abgeschlossen"
            CrawlStatus.CANCELLED -> "Crawl abgebrochen"
            else -> "Crawl fehlgeschlagen"
        }
        val done = NotificationCompat.Builder(this, DocuCrawlerApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("${state.pagesSaved} Seiten · ${state.errors} Fehler – tippen zum Exportieren")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_DONE_ID, done)
        stopSelf()
    }

    private fun maybeUpdateNotification(p: WikiCrawler.CrawlProgress) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate < 1000) return
        lastNotificationUpdate = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildProgressNotification("${p.pagesSaved} Seiten · Queue ${p.queueSize}", p.currentUrl, p.pagesSaved, p.queueSize))
    }

    private fun buildProgressNotification(text: String, url: String?, done: Int, queued: Int): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, WikiCrawlerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val total = done + queued
        return NotificationCompat.Builder(this, DocuCrawlerApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DocuCrawler läuft")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, url).joinToString("\n")))
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "Abbrechen", stopIntent)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DocuCrawler::crawl").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // max. 6 h (Limit für dataSync-Services ab Android 15)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15: dataSync-Zeitlimit erreicht → sauber beenden.
        crawlJob?.cancel()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.docucrawler.action.START"
        const val ACTION_STOP = "dev.docucrawler.action.STOP"
        private const val EXTRA_CONFIG = "config"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_DONE_ID = 43

        fun start(context: Context, config: CrawlConfig) {
            val intent = Intent(context, WikiCrawlerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WikiCrawlerService::class.java).setAction(ACTION_STOP))
        }
    }
}
