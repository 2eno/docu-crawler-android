package dev.docucrawler.android.crawler

import android.webkit.CookieManager

/**
 * Bridge zwischen OkHttp und dem WebView-Cookie-Store.
 *
 * Liest bei jedem Request live über `CookieManager.getInstance().getCookie(url)` – so werden auch
 * HttpOnly-Session-Cookies und Cookies, die der Server während des Crawls erneuert, berücksichtigt.
 * Fällt auf den beim Login erfassten Snapshot zurück, falls der CookieManager nichts liefert.
 */
class WebViewCookieProvider(
    private val snapshotHeader: String?,
    private val snapshotHost: String?,
) : CookieProvider {

    private val cookieManager: CookieManager = CookieManager.getInstance()

    override fun cookieHeaderFor(url: String): String? {
        val live = cookieManager.getCookie(url)
        if (!live.isNullOrBlank()) return live
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        return if (host != null && snapshotHost != null && host.endsWith(snapshotHost, ignoreCase = true)) {
            snapshotHeader
        } else null
    }

    override fun storeSetCookie(url: String, setCookieHeader: String) {
        cookieManager.setCookie(url, setCookieHeader)
    }

    fun flush() = cookieManager.flush()
}
