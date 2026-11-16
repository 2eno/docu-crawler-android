package dev.docucrawler.android.crawler

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Alle Parameter eines Crawl-Laufs. Parcelable, damit die Konfiguration per Intent
 * an den [dev.docucrawler.android.service.WikiCrawlerService] übergeben werden kann.
 */
@Parcelize
data class CrawlConfig(
    val startUrl: String,
    val projectName: String,
    /** 0 = nur die Startseite, 1 = Startseite + direkt verlinkte Seiten, ... */
    val maxDepth: Int = 3,
    /** Wartezeit zwischen zwei Requests desselben Workers in Millisekunden. */
    val delayMs: Long = 500,
    /** Anzahl paralleler Verbindungen/Worker (1–16). */
    val concurrency: Int = 4,
    /** Bilder des Inhalts nach `assets/` herunterladen und Links lokal umschreiben. */
    val downloadImages: Boolean = false,
    /** Originalbilder statt eingebetteter Vorschaubilder laden (Fallback: Vorschaubild). */
    val preferOriginalImages: Boolean = false,
    /** Maximale Größe eines Bildes in Bytes. */
    val maxImageBytes: Long = 5L * 1024 * 1024,
    /** Optionaler CSS-Selektor für den Hauptinhalt, z. B. `main`, `article`, `#mw-content-text`. */
    val contentSelector: String? = null,
    /** Nur Links unterhalb des Pfad-Präfixes der Start-URL verfolgen (sonst: ganze Domain). */
    val restrictToPathPrefix: Boolean = true,
    /** Sicherheitslimit; 0 = unbegrenzt. */
    val maxPages: Int = 0,
    /** URLs, die einen dieser Teilstrings enthalten, werden nie abgerufen (z. B. Logout-Links!). */
    val excludePatterns: List<String> = DEFAULT_EXCLUDES,
) : Parcelable {
    companion object {
        val DEFAULT_EXCLUDES = listOf(
            "logout", "log_out", "log-out", "signout", "sign_out", "sign-out", "abmelden",
            "action=edit", "action=history", "action=delete", "action=raw", "oldid=", "diff=",
            "Special:", "Spezial:", "/edit/", "printable=yes",
        )
    }
}
