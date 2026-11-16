package dev.docucrawler.android.crawler

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlUtils {

    private val NON_HTML_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "bmp", "tif", "tiff",
        "pdf", "zip", "gz", "tgz", "tar", "rar", "7z", "bz2", "xz",
        "mp3", "mp4", "m4a", "wav", "ogg", "webm", "avi", "mov", "mkv",
        "css", "js", "mjs", "json", "xml", "rss", "atom", "txt", "csv",
        "woff", "woff2", "ttf", "otf", "eot",
        "exe", "dmg", "apk", "msi", "deb", "rpm", "iso", "bin",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods",
    )

    private val STRIP_EXTENSIONS = setOf("html", "htm", "xhtml", "php", "asp", "aspx", "jsp", "shtml")

    /** Entfernt Fragment, normalisiert Schema/Host (OkHttp erledigt Kleinschreibung + Default-Port). */
    fun normalize(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        return parsed.newBuilder().fragment(null).build().toString()
    }

    /**
     * Pfad-Präfix der Start-URL: alles bis inkl. letztem `/`.
     * `https://x/wiki/Main_Page` → `/wiki/`, `https://x/docs/` → `/docs/`.
     */
    fun pathPrefixOf(startUrl: HttpUrl): String {
        val path = startUrl.encodedPath
        return path.substring(0, path.lastIndexOf('/') + 1).ifEmpty { "/" }
    }

    fun isInScope(url: HttpUrl, start: HttpUrl, pathPrefix: String?): Boolean {
        if (url.scheme != "http" && url.scheme != "https") return false
        if (!url.host.equals(start.host, ignoreCase = true)) return false
        if (pathPrefix != null && !url.encodedPath.startsWith(pathPrefix)) {
            // `/docs` soll auch in Scope sein, wenn das Präfix `/docs/` ist.
            if (url.encodedPath.trimEnd('/') + "/" != pathPrefix) return false
        }
        return true
    }

    fun looksLikeHtml(url: HttpUrl): Boolean {
        val last = url.pathSegments.lastOrNull().orEmpty()
        val ext = last.substringAfterLast('.', "").lowercase()
        return ext.isEmpty() || ext !in NON_HTML_EXTENSIONS
    }

    fun matchesExclude(url: String, patterns: List<String>): Boolean =
        patterns.any { it.isNotBlank() && url.contains(it.trim(), ignoreCase = true) }

    /**
     * Bildet eine URL auf einen relativen Dateipfad ab, der der URL-Pfadstruktur entspricht.
     *
     * - `/docs/guide/install`       → `docs/guide/install.md`
     * - `/docs/guide/`              → `docs/guide/index.md`
     * - `/docs/page.html`           → `docs/page.md`
     * - `/index.php?title=Foo_Bar`  → `index__title=Foo_Bar.md`
     */
    fun toRelativeFilePath(url: HttpUrl): String {
        val rawSegments = url.pathSegments // bereits URL-dekodiert
        val endsWithSlash = rawSegments.isEmpty() || rawSegments.last().isEmpty()
        val segments = rawSegments.filter { it.isNotEmpty() }.map(::sanitizeSegment).toMutableList()

        if (endsWithSlash || segments.isEmpty()) {
            segments += "index"
        } else {
            val last = segments.removeAt(segments.lastIndex)
            val ext = last.substringAfterLast('.', "").lowercase()
            segments += if (ext in STRIP_EXTENSIONS) last.substringBeforeLast('.') else last
        }

        url.query?.takeIf { it.isNotBlank() }?.let { q ->
            val last = segments.removeAt(segments.lastIndex)
            segments += sanitizeSegment(last + "__" + q).take(150)
        }
        return segments.joinToString("/") + ".md"
    }

    fun sanitizeSegment(segment: String): String {
        val cleaned = segment
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .replace(Regex("""\s+"""), "_")
            .trim('.', ' ', '_')
            .take(120)
        return cleaned.ifEmpty { "_" }
    }

    /** Projektname aus URL ableiten, z. B. `wiki.example.com_docs`. */
    fun suggestProjectName(url: String): String {
        val parsed = normalize(url)?.toHttpUrlOrNull() ?: return "crawl"
        val firstSegment = parsed.pathSegments.firstOrNull { it.isNotBlank() }
        return sanitizeSegment(listOfNotNull(parsed.host, firstSegment).joinToString("_"))
    }
}
