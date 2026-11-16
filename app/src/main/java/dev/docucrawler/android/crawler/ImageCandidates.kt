package dev.docucrawler.android.crawler

import org.jsoup.nodes.Element

/**
 * Ermittelt für ein `<img>` die URLs, die in Reihenfolge der Präferenz geladen werden sollen.
 * Mit [preferOriginal] wird versucht, statt des eingebetteten Vorschaubilds das Original zu bekommen;
 * die eingebettete `src` bleibt immer als letzter Fallback in der Liste.
 */
object ImageCandidates {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "avif", "bmp", "tif", "tiff")

    /** MediaWiki: `/images/thumb/a/ab/Foto.jpg/220px-Foto.jpg` → `/images/a/ab/Foto.jpg` */
    private val MEDIAWIKI_THUMB = Regex("""^(.*?/)thumb/(.+?)/[^/]*$""")

    fun resolve(img: Element, preferOriginal: Boolean): List<String> {
        val src = img.absUrl("src").ifEmpty { img.attr("src") }
        if (!preferOriginal) return listOfNotNull(src.takeIf { it.isNotBlank() })

        val candidates = LinkedHashSet<String>()
        mediaWikiOriginal(src)?.let(candidates::add)
        linkedOriginal(img)?.let(candidates::add)
        largestFromSrcset(img)?.let(candidates::add)
        if (src.isNotBlank()) candidates += src
        return candidates.toList()
    }

    fun mediaWikiOriginal(url: String): String? {
        val path = url.substringBefore('?')
        val m = MEDIAWIKI_THUMB.matchEntire(path) ?: return null
        val original = m.groupValues[1] + m.groupValues[2]
        return original.takeIf { isImagePath(it) }
    }

    /** `<a href="…/bild.jpg"><img …></a>` – Link zeigt direkt auf eine Bilddatei. */
    private fun linkedOriginal(img: Element): String? {
        val link = img.closest("a[href]") ?: return null
        val href = link.absUrl("href").ifEmpty { return null }
        return href.takeIf { isImagePath(it.substringBefore('?').substringBefore('#')) }
    }

    /** Größte Variante aus `srcset` (`… 220w, … 440w` bzw. `… 1.5x, … 2x`). */
    private fun largestFromSrcset(img: Element): String? {
        val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }.ifBlank { return null }
        return srcset.split(',')
            .mapNotNull { entry ->
                val parts = entry.trim().split(Regex("\\s+"))
                val url = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val descriptor = parts.getOrNull(1).orEmpty()
                val weight = descriptor.dropLast(1).toDoubleOrNull()?.let {
                    if (descriptor.endsWith("x")) it * 10_000 else it
                } ?: 1.0
                url to weight
            }
            .maxByOrNull { it.second }
            ?.first
            ?.let { resolveAgainst(img, it) }
    }

    private fun resolveAgainst(img: Element, url: String): String = try {
        java.net.URI(img.baseUri()).resolve(url).toString()
    } catch (_: Exception) {
        url
    }

    private fun isImagePath(path: String): Boolean {
        val last = path.substringAfterLast('/')
        // `Datei:Foto.jpg`, `File:…`, `Bild:…` sind MediaWiki-Beschreibungsseiten (HTML), keine Bilder.
        if (last.contains(':') || last.contains("%3A", ignoreCase = true)) return false
        return last.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }
}
