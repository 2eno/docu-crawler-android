package dev.docucrawler.android.crawler

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lädt Bilder der gecrawlten Seiten nach `<projekt>/assets/` herunter.
 *
 * - Jede Bild-URL wird genau einmal geladen (auch bei mehreren Workern gleichzeitig).
 * - Nur echte Bilder (`Content-Type: image/…`) bis [maxBytes] werden gespeichert.
 * - Cookies / HTTP-Auth hängt der gemeinsame OkHttp-Client per Interceptor an – nur für den passenden Host.
 * - Dateinamen: `<name>-<hash>.<ext>` – kurz, eindeutig, ohne Sonderzeichen.
 */
class ImageDownloader(
    private val client: OkHttpClient,
    private val projectDir: File,
    private val userAgent: String,
    private val maxBytes: Long,
    private val log: (LogLevel, String) -> Unit,
) {
    /** URL → relativer Pfad ab Projektordner (`assets/…`), oder "" wenn nicht ladbar. */
    private val cache = ConcurrentHashMap<String, String>()

    val saved = AtomicInteger()
    val failed = AtomicInteger()
    val bytes = AtomicLong()

    /** Liefert `assets/<datei>` (relativ zum Projektordner) oder `null`, wenn das Bild nicht geladen wurde. */
    fun localPathFor(url: String): String? =
        cache.computeIfAbsent(url) { download(it) }.ifEmpty { null }

    private fun download(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        return try {
            val request = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.5")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    failed.incrementAndGet()
                    log(LogLevel.WARN, "  Bild HTTP ${response.code}: $url")
                    return ""
                }
                val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (!type.startsWith("image/")) {
                    log(LogLevel.DEBUG, "  Kein Bild ($type): $url")
                    return ""
                }
                val body = response.body ?: return ""
                if (body.contentLength() > maxBytes) {
                    log(LogLevel.WARN, "  Bild zu groß (${body.contentLength() / 1024} KB), übersprungen: $url")
                    return ""
                }
                val relPath = "$ASSETS_DIR/${fileName(httpUrl, type)}"
                val target = File(projectDir, relPath)
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".part")
                var written = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            if (written > maxBytes) {
                                out.close(); tmp.delete()
                                log(LogLevel.WARN, "  Bild zu groß (> ${maxBytes / 1024} KB), übersprungen: $url")
                                return ""
                            }
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete(); return ""
                }
                saved.incrementAndGet()
                bytes.addAndGet(written)
                log(LogLevel.SUCCESS, "  🖼 $relPath")
                relPath
            }
        } catch (e: Exception) {
            failed.incrementAndGet()
            log(LogLevel.WARN, "  Bild fehlgeschlagen: $url (${e.message ?: e.javaClass.simpleName})")
            ""
        }
    }

    companion object {
        const val ASSETS_DIR = "assets"

        private val EXT_BY_TYPE = mapOf(
            "image/jpeg" to "jpg", "image/jpg" to "jpg", "image/png" to "png", "image/gif" to "gif",
            "image/webp" to "webp", "image/svg+xml" to "svg", "image/avif" to "avif", "image/bmp" to "bmp",
            "image/x-icon" to "ico", "image/vnd.microsoft.icon" to "ico", "image/tiff" to "tif",
        )

        fun fileName(url: HttpUrl, contentType: String): String {
            val last = url.pathSegments.lastOrNull { it.isNotBlank() }.orEmpty()
            val urlExt = last.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 }
            val ext = EXT_BY_TYPE[contentType] ?: urlExt ?: "img"
            val base = last.substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .take(60)
                .ifEmpty { "bild" }
            return "$base-${shortHash(url.toString())}.$ext"
        }

        private fun shortHash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }

        /** Relativer Link von einer Markdown-Datei (`docs/a/b.md`) zu einem Asset (`assets/x.png`). */
        fun relativeLink(markdownRelPath: String, assetRelPath: String): String =
            "../".repeat(markdownRelPath.count { it == '/' }) + assetRelPath
    }
}
