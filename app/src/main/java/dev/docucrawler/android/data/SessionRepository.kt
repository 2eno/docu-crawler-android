package dev.docucrawler.android.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import dev.docucrawler.android.crawler.HttpCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** Session, die nach dem WebView-Login übernommen wurde. */
data class SessionData(
    val url: String,
    val host: String,
    val cookieHeader: String,
    val userAgent: String,
    /** HTTP-Basic-Auth-Zugangsdaten, falls die Seite per `WWW-Authenticate` geschützt ist. */
    val httpAuth: HttpCredentials? = null,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    val cookieNames: List<String>
        get() = cookieHeader.split(';').mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
}

/**
 * Hält die aktuelle Session (Cookies + User-Agent) prozessweit.
 * Die Cookies selbst liegen persistent im WebView-CookieManager; hier wird nur der Snapshot
 * samt Metadaten gehalten, der für UI und Crawler-Start benötigt wird.
 */
object SessionRepository {

    private val _session = MutableStateFlow<SessionData?>(null)
    val session: StateFlow<SessionData?> = _session.asStateFlow()

    /**
     * Im Login-Browser eingegebene HTTP-Auth-Zugangsdaten. Bewusst nur im Arbeitsspeicher –
     * das Passwort wird nie auf die Platte geschrieben.
     */
    @Volatile
    private var pendingHttpAuth: HttpCredentials? = null

    fun rememberHttpAuth(credentials: HttpCredentials) {
        pendingHttpAuth = credentials
    }

    /**
     * Liest alle aktiven Cookies für [url] (und – falls abweichend – für die zuletzt besuchte
     * [currentUrl]) über `CookieManager.getInstance().getCookie(url)` aus.
     */
    fun captureFromWebView(url: String, currentUrl: String?, userAgent: String): SessionData? {
        val cm = CookieManager.getInstance()
        cm.flush()
        val sources = buildList {
            cm.getCookie(url)?.let(::add)
            if (currentUrl != null && currentUrl != url && sameHost(url, currentUrl)) {
                cm.getCookie(currentUrl)?.let(::add)
            }
        }
        val header = mergeCookieHeaders(sources)
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val auth = pendingHttpAuth?.takeIf { it.appliesTo(host) }
        if (header.isBlank() && auth == null) {
            _session.value = null
            return null
        }
        val data = SessionData(
            url = url,
            host = host,
            cookieHeader = header,
            userAgent = userAgent,
            httpAuth = auth,
        )
        _session.value = data
        return data
    }

    fun clear() {
        pendingHttpAuth = null
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        _session.value = null
    }

    /** User-Agent, den WebView und Crawler gemeinsam verwenden. */
    fun defaultUserAgent(context: Context): String = sanitizeUserAgent(WebSettings.getDefaultUserAgent(context))

    /**
     * Entfernt die WebView-Kennung (`; wv`), da manche SSO-/OAuth-Anbieter Logins in
     * eingebetteten WebViews blockieren.
     */
    fun sanitizeUserAgent(ua: String): String = ua.replace("; wv)", ")").replace(" Version/4.0", "")

    private fun mergeCookieHeaders(headers: List<String>): String {
        val map = LinkedHashMap<String, String>()
        headers.flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { pair -> map[pair.substringBefore('=')] = pair }
        return map.values.joinToString("; ")
    }

    private fun sameHost(a: String, b: String): Boolean =
        runCatching { URI(a).host.equals(URI(b).host, ignoreCase = true) }.getOrDefault(false)
}
