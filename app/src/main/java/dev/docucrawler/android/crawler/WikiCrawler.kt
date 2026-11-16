package dev.docucrawler.android.crawler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native Kotlin-Portierung der docu-crawler-Logik:
 * Breadth-First-Search ab der Start-URL (mit N parallelen Workern), Filterung per Domain/Pfad-Präfix,
 * HTML → Markdown und Ablage entsprechend der URL-Pfadstruktur.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit die Engine per JVM-Unit-Test prüfbar ist.
 */
class WikiCrawler(
    private val config: CrawlConfig,
    private val outputDir: File,
    private val cookieProvider: CookieProvider,
    private val userAgent: String,
    private val listener: Listener,
    /** Optionale HTTP-Basic-Auth-Zugangsdaten (präemptiv an den gleichen Host gesendet). */
    private val httpAuth: HttpCredentials? = null,
    clientOverride: OkHttpClient? = null,
) {
    interface Listener {
        fun onProgress(progress: CrawlProgress)
        fun onLog(level: LogLevel, message: String)
    }

    data class CrawlProgress(
        val pagesSaved: Int,
        val queueSize: Int,
        val visited: Int,
        val errors: Int,
        val skipped: Int,
        val currentUrl: String?,
        val currentDepth: Int,
    )

    data class Summary(val pagesSaved: Int, val errors: Int, val skipped: Int, val outputDir: File)

    private data class QueueItem(val url: HttpUrl, val depth: Int)

    private val converter = MarkdownConverter()

    private val client: OkHttpClient = (clientOverride ?: OkHttpClient()).newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .connectionPool(ConnectionPool(MAX_CONCURRENCY, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequests = MAX_CONCURRENCY; maxRequestsPerHost = MAX_CONCURRENCY })
        // Network-Interceptor: läuft für jeden Redirect-Hop einzeln → Cookies immer passend zur Ziel-URL.
        .addNetworkInterceptor(SessionInterceptor())
        .build()

    private val pagesSaved = AtomicInteger()
    private val errors = AtomicInteger()
    private val skipped = AtomicInteger()

    // ---- Gemeinsamer Zustand aller Worker, geschützt durch [lock] ----
    private val lock = Mutex()
    private val queue = ArrayDeque<QueueItem>()
    private val seen = HashSet<String>()
    private val usedPaths = HashSet<String>()
    private var inFlight = 0
    private val limitLogged = AtomicBoolean(false)

    private lateinit var start: HttpUrl
    private var pathPrefix: String? = null

    suspend fun run(): Summary = withContext(Dispatchers.IO) {
        start = UrlUtils.normalize(config.startUrl)?.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Ungültige Start-URL: ${config.startUrl}")
        pathPrefix = if (config.restrictToPathPrefix) UrlUtils.pathPrefixOf(start) else null
        val workers = config.concurrency.coerceIn(1, MAX_CONCURRENCY)

        outputDir.mkdirs()
        listener.onLog(LogLevel.INFO, "Starte Crawl: $start")
        listener.onLog(
            LogLevel.INFO,
            "Bereich: ${start.host}${pathPrefix ?: "/ (ganze Domain)"} · Tiefe ${config.maxDepth} · " +
                "$workers parallel · Delay ${config.delayMs} ms" +
                (config.contentSelector?.takeIf { it.isNotBlank() }?.let { " · Selektor '$it'" } ?: "")
        )
        val hasCookies = !cookieProvider.cookieHeaderFor(start.toString()).isNullOrBlank()
        val hasAuth = httpAuth?.appliesTo(start.host) == true
        if (hasAuth) listener.onLog(LogLevel.INFO, "HTTP-Authentifizierung aktiv (Benutzer '${httpAuth!!.username}').")
        if (!hasCookies && !hasAuth) {
            listener.onLog(LogLevel.WARN, "Keine Session-Cookies vorhanden – crawle anonym.")
        }

        queue += QueueItem(start, 0)
        seen += start.toString()

        coroutineScope {
            repeat(workers) { id -> launch { worker(id, workers) } }
        }

        listener.onLog(
            LogLevel.INFO,
            "Fertig: ${pagesSaved.get()} Seiten gespeichert, ${errors.get()} Fehler, ${skipped.get()} übersprungen."
        )
        Summary(pagesSaved.get(), errors.get(), skipped.get(), outputDir)
    }

    /**
     * Ein Worker holt sich Einträge aus der gemeinsamen BFS-Warteschlange, bis sie leer ist und
     * kein anderer Worker mehr Seiten verarbeitet (die noch neue Links liefern könnten).
     * Der Delay gilt pro Worker – Gesamtrate ≈ Worker / (Delay + Antwortzeit).
     */
    private suspend fun worker(id: Int, workers: Int) {
        // Starts staffeln, damit nicht alle Worker im selben Moment anfragen.
        if (id > 0 && config.delayMs > 0) delay(config.delayMs * id / workers)
        var first = true
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val item = lock.withLock {
                if (limitReached()) return
                queue.removeFirstOrNull()?.also { inFlight++ }
            }
            if (item == null) {
                if (lock.withLock { queue.isEmpty() && inFlight == 0 }) return
                delay(IDLE_POLL_MS) // auf neue Links anderer Worker warten
                continue
            }
            try {
                if (!first && config.delayMs > 0) delay(config.delayMs)
                first = false
                report(item)
                process(item)
            } finally {
                lock.withLock { inFlight-- }
            }
            report(null)
        }
    }

    private fun limitReached(): Boolean {
        if (config.maxPages <= 0 || pagesSaved.get() + inFlight < config.maxPages) return false
        if (limitLogged.compareAndSet(false, true)) {
            listener.onLog(LogLevel.WARN, "Seitenlimit (${config.maxPages}) erreicht – stoppe.")
        }
        return true
    }

    private suspend fun process(item: QueueItem) {
        try {
            val (finalUrl, html) = fetch(item.url) ?: return

            if (finalUrl.toString() != item.url.toString()) {
                if (!UrlUtils.isInScope(finalUrl, start, pathPrefix)) {
                    skipped.incrementAndGet()
                    listener.onLog(
                        LogLevel.WARN,
                        "Weiterleitung aus dem Bereich heraus ($finalUrl) – evtl. Login/Session abgelaufen."
                    )
                    return
                }
                // Redirect-Ziel bereits bekannt (in Queue oder verarbeitet)? Dann Duplikat vermeiden.
                if (!lock.withLock { seen.add(finalUrl.toString()) }) {
                    listener.onLog(LogLevel.DEBUG, "Weiterleitung auf bereits bekannte Seite: $finalUrl")
                    return
                }
            }

            val doc = Jsoup.parse(html, finalUrl.toString())

            // Links für BFS extrahieren (vor der Konvertierung, die das DOM verändert).
            if (item.depth < config.maxDepth) {
                val candidates = doc.select("a[href]").mapNotNull { a ->
                    val abs = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val normalized = UrlUtils.normalize(abs)?.toHttpUrlOrNull() ?: return@mapNotNull null
                    normalized.takeIf {
                        UrlUtils.isInScope(it, start, pathPrefix) &&
                            UrlUtils.looksLikeHtml(it) &&
                            !UrlUtils.matchesExclude(it.toString(), config.excludePatterns)
                    }
                }
                val added = lock.withLock {
                    candidates.count { url ->
                        seen.add(url.toString()).also { isNew -> if (isNew) queue += QueueItem(url, item.depth + 1) }
                    }
                }
                if (added > 0) listener.onLog(LogLevel.DEBUG, "  +$added neue Links (Tiefe ${item.depth + 1})")
            }

            val result = converter.convert(doc, config.contentSelector)
            if (!result.selectorMatched) {
                listener.onLog(LogLevel.WARN, "  Selektor '${config.contentSelector}' ohne Treffer – nutze <body>.")
            }
            if (result.markdown.isBlank()) {
                skipped.incrementAndGet()
                listener.onLog(LogLevel.WARN, "  Kein Inhalt – übersprungen: ${item.url}")
                return
            }

            val relPath = lock.withLock { uniquePath(UrlUtils.toRelativeFilePath(finalUrl), usedPaths) }
            val file = File(outputDir, relPath)
            file.parentFile?.mkdirs()
            file.writeText(buildFrontMatter(result.title, finalUrl) + result.markdown)
            pagesSaved.incrementAndGet()
            listener.onLog(LogLevel.SUCCESS, "✓ $relPath")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.incrementAndGet()
            listener.onLog(LogLevel.ERROR, "✗ ${item.url}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Lädt eine Seite; gibt `null` zurück, wenn sie kein HTML ist oder nicht geladen werden konnte. */
    private suspend fun fetch(url: HttpUrl): Pair<HttpUrl, String>? {
        var attempt = 0
        while (true) {
            attempt++
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .header("Accept-Language", "de,en;q=0.8")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                if ((code == 429 || code in 500..599) && attempt < 3) {
                    val wait = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                        ?: (config.delayMs.coerceAtLeast(1000) * attempt * 2)
                    listener.onLog(LogLevel.WARN, "HTTP $code für $url – neuer Versuch in ${wait / 1000}s")
                    delay(wait.coerceAtMost(60_000))
                    return@use null
                }
                if (!response.isSuccessful) {
                    if (code == 401 || code == 403) {
                        val hint = if (code == 401 && response.header("WWW-Authenticate") != null) {
                            if (httpAuth == null) "HTTP-Login nötig – im Login-Browser anmelden" else "Benutzername/Passwort falsch?"
                        } else "Session gültig?"
                        listener.onLog(LogLevel.ERROR, "HTTP $code für $url – Zugriff verweigert ($hint)")
                        errors.incrementAndGet()
                    } else {
                        listener.onLog(LogLevel.WARN, "HTTP $code für $url – übersprungen")
                        skipped.incrementAndGet()
                    }
                    return null
                }
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.isNotEmpty() && !contentType.contains("html", ignoreCase = true)) {
                    skipped.incrementAndGet()
                    listener.onLog(LogLevel.DEBUG, "Kein HTML ($contentType): $url")
                    return null
                }
                val body = response.body?.string() ?: throw IOException("Leere Antwort")
                return response.request.url to body
            } ?: continue
        }
    }

    private fun uniquePath(path: String, used: MutableSet<String>): String {
        if (used.add(path.lowercase())) return path
        val base = path.removeSuffix(".md")
        var i = 2
        while (!used.add("${base}_$i.md".lowercase())) i++
        return "${base}_$i.md"
    }

    private fun buildFrontMatter(title: String, url: HttpUrl): String {
        val safeTitle = title.replace("\"", "\\\"")
        val now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return buildString {
            append("---\n")
            append("title: \"").append(safeTitle).append("\"\n")
            append("source: ").append(url).append('\n')
            append("crawled_at: ").append(now).append('\n')
            append("---\n\n")
            if (title.isNotBlank()) append("# ").append(title).append("\n\n")
        }
    }

    private suspend fun report(current: QueueItem?) {
        val (queued, visited) = lock.withLock { queue.size to seen.size }
        listener.onProgress(
            CrawlProgress(
                pagesSaved = pagesSaved.get(),
                queueSize = queued,
                visited = visited,
                errors = errors.get(),
                skipped = skipped.get(),
                currentUrl = current?.url?.toString(),
                currentDepth = current?.depth ?: 0,
            )
        )
    }

    /** Hängt Session-Cookies (und ggf. Basic-Auth) an jeden Request und übernimmt `Set-Cookie` zurück in den Store. */
    private inner class SessionInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val url = req.url.toString()
            val cookie = cookieProvider.cookieHeaderFor(url)
            val builder = req.newBuilder()
            if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
            if (httpAuth != null && httpAuth.appliesTo(req.url.host) && req.header("Authorization") == null) {
                builder.header("Authorization", httpAuth.basicHeader())
            }
            val newReq = builder.build()
            val response = chain.proceed(newReq)
            response.headers("Set-Cookie").forEach { cookieProvider.storeSetCookie(url, it) }
            return response
        }
    }
}

private const val MAX_CONCURRENCY = 16
private const val IDLE_POLL_MS = 50L

enum class LogLevel { DEBUG, INFO, SUCCESS, WARN, ERROR }
