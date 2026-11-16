package dev.docucrawler.android.crawler

/**
 * Abstraktion über den Cookie-Speicher, damit die Crawler-Engine ohne Android-Abhängigkeit
 * testbar bleibt. Die Android-Implementierung ([WebViewCookieProvider]) delegiert an
 * `android.webkit.CookieManager`.
 */
interface CookieProvider {
    /** Wert für den `Cookie`-Header, oder `null`, wenn keine Cookies vorhanden sind. */
    fun cookieHeaderFor(url: String): String?

    /** Wird für jeden `Set-Cookie`-Header einer Antwort aufgerufen (Session-Refresh). */
    fun storeSetCookie(url: String, setCookieHeader: String)
}

/** Einfacher In-Memory-Provider (Tests / Crawls ohne Login). */
class StaticCookieProvider(private val header: String? = null) : CookieProvider {
    override fun cookieHeaderFor(url: String): String? = header?.takeIf { it.isNotBlank() }
    override fun storeSetCookie(url: String, setCookieHeader: String) = Unit
}
