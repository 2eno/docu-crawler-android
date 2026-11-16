package dev.docucrawler.android.crawler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import kotlin.coroutines.coroutineContext

/**
 * Native Kotlin-Portierung der docu-crawler-Logik:
 * Breadth-First-Search ab der Start-URL, Filterung per Domain/Pfad-Präfix,
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
        // Network-Interceptor: läuft für jeden Redirect-Hop einzeln → Cookies immer passend zur Ziel-URL.
        .addNetworkInterceptor(SessionInterceptor())
        .build()

    private var pagesSaved = 0
    private var errors = 0
    private var skipped = 0

    suspend fun run(): Summary = withContext(Dispatchers.IO) {
        val start = UrlUtils.normalize(config.startUrl)?.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Ungültige Start-URL: ${config.startUrl}")
        val pathPrefix = if (config.restrictToPathPrefix) UrlUtils.pathPrefixOf(start) else null

        outputDir.mkdirs()
        listener.onLog(LogLevel.INFO, "Starte Crawl: $start")
        listener.onLog(
            LogLevel.INFO,
            "Bereich: ${start.host}${pathPrefix ?: "/ (ganze Domain)"} · Tiefe ${config.maxDepth} · Delay ${config.delayMs} ms" +
                (config.contentSelector?.takeIf { it.isNotBlank() }?.let { " · Selektor '$it'" } ?: "")
        )
        if (cookieProvider.cookieHeaderFor(start.toString()).isNullOrBlank()) {
            listener.onLog(LogLevel.WARN, "Keine Session-Cookies vorhanden – crawle anonym.")
        }

        val queue = ArrayDeque<QueueItem>()
        val seen = HashSet<String>()
        val usedPaths = HashSet<String>()
        queue += QueueItem(start, 0)
        seen += start.toString()
        var firstRequest = true

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (config.maxPages in 1..pagesSaved) {
                listener.onLog(LogLevel.WARN, "Seitenlimit (${config.maxPages}) erreicht – stoppe.")
                break
            }
            val item = queue.removeFirst()
            report(queue.size, seen.size, item)

            if (!firstRequest && config.delayMs > 0) delay(config.delayMs)
            firstRequest = false

            try {
                val page = fetch(item.url) ?: continue
                val (finalUrl, html) = page

                if (finalUrl.toString() != item.url.toString()) {
                    if (!UrlUtils.isInScope(finalUrl, start, pathPrefix)) {
                        skipped++
                        listener.onLog(
                            LogLevel.WARN,
                            "Weiterleitung aus dem Bereich heraus ($finalUrl) – evtl. Login/Session abgelaufen."
                        )
                        continue
                    }
                    // Redirect-Ziel bereits bekannt (in Queue oder verarbeitet)? Dann Duplikat vermeiden.
                    if (!seen.add(finalUrl.toString())) {
                        listener.onLog(LogLevel.DEBUG, "Weiterleitung auf bereits bekannte Seite: $finalUrl")
                        continue
                    }
                }

                val doc = Jsoup.parse(html, finalUrl.toString())

                // Links für BFS extrahieren (vor der Konvertierung, die das DOM verändert).
                if (item.depth < config.maxDepth) {
                    var added = 0
                    for (a in doc.select("a[href]")) {
                        val abs = a.absUrl("href").takeIf { it.isNotEmpty() } ?: continue
                        val normalized = UrlUtils.normalize(abs)?.toHttpUrlOrNull() ?: continue
                        val key = normalized.toString()
                        if (key in seen) continue
                        if (!UrlUtils.isInScope(normalized, start, pathPrefix)) continue
                        if (!UrlUtils.looksLikeHtml(normalized)) continue
                        if (UrlUtils.matchesExclude(key, config.excludePatterns)) continue
                        seen += key
                        queue += QueueItem(normalized, item.depth + 1)
                        added++
                    }
                    if (added > 0) listener.onLog(LogLevel.DEBUG, "  +$added neue Links (Tiefe ${item.depth + 1})")
                }

                val result = converter.convert(doc, config.contentSelector)
                if (!result.selectorMatched) {
                    listener.onLog(LogLevel.WARN, "  Selektor '${config.contentSelector}' ohne Treffer – nutze <body>.")
                }
                if (result.markdown.isBlank()) {
                    skipped++
                    listener.onLog(LogLevel.WARN, "  Kein Inhalt – übersprungen.")
                    continue
                }

                val relPath = uniquePath(UrlUtils.toRelativeFilePath(finalUrl), usedPaths)
                val file = File(outputDir, relPath)
                file.parentFile?.mkdirs()
                file.writeText(buildFrontMatter(result.title, finalUrl) + result.markdown)
                pagesSaved++
                listener.onLog(LogLevel.SUCCESS, "✓ $relPath")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors++
                listener.onLog(LogLevel.ERROR, "✗ ${item.url}: ${e.message ?: e.javaClass.simpleName}")
            }
            report(queue.size, seen.size, null)
        }

        listener.onLog(LogLevel.INFO, "Fertig: $pagesSaved Seiten gespeichert, $errors Fehler, $skipped übersprungen.")
        Summary(pagesSaved, errors, skipped, outputDir)
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
                        listener.onLog(LogLevel.ERROR, "HTTP $code für $url – Zugriff verweigert (Session gültig?)")
                        errors++
                    } else {
                        listener.onLog(LogLevel.WARN, "HTTP $code für $url – übersprungen")
                        skipped++
                    }
                    return null
                }
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.isNotEmpty() && !contentType.contains("html", ignoreCase = true)) {
                    skipped++
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

    private fun report(queueSize: Int, visited: Int, current: QueueItem?) {
        listener.onProgress(
            CrawlProgress(
                pagesSaved = pagesSaved,
                queueSize = queueSize,
                visited = visited,
                errors = errors,
                skipped = skipped,
                currentUrl = current?.url?.toString(),
                currentDepth = current?.depth ?: 0,
            )
        )
    }

    /** Hängt Session-Cookies an jeden Request und übernimmt `Set-Cookie` zurück in den Store. */
    private inner class SessionInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val url = req.url.toString()
            val cookie = cookieProvider.cookieHeaderFor(url)
            val newReq = if (!cookie.isNullOrBlank()) req.newBuilder().header("Cookie", cookie).build() else req
            val response = chain.proceed(newReq)
            response.headers("Set-Cookie").forEach { cookieProvider.storeSetCookie(url, it) }
            return response
        }
    }
}

enum class LogLevel { DEBUG, INFO, SUCCESS, WARN, ERROR }
