package dev.docucrawler.android.crawler

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.util.data.MutableDataSet
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Wandelt eine (bereits geparste) HTML-Seite in sauberes Markdown um.
 * 1. Störende Elemente entfernen (Scripts, Styles, Navigation, ...).
 * 2. Optional per CSS-Selektor auf den Hauptinhalt einschränken.
 * 3. Relative Links/Bilder absolut machen.
 * 4. Mit flexmark-html2md-converter konvertieren.
 */
class MarkdownConverter {

    private val converter: FlexmarkHtmlConverter = FlexmarkHtmlConverter.builder(
        MutableDataSet().apply {
            set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
            set(FlexmarkHtmlConverter.OUTPUT_ATTRIBUTES_ID, false)
            set(FlexmarkHtmlConverter.OUTPUT_UNKNOWN_TAGS, false)
            set(FlexmarkHtmlConverter.RENDER_COMMENTS, false)
            set(FlexmarkHtmlConverter.DIV_TABLE_PROCESSING, true)
            set(FlexmarkHtmlConverter.MAX_BLANK_LINES, 2)
        }
    ).build()

    data class Result(
        val title: String,
        val markdown: String,
        /** `false`, wenn der CSS-Selektor nichts gefunden hat und auf `<body>` zurückgefallen wurde. */
        val selectorMatched: Boolean,
    )

    /**
     * @param imageRewriter optional: bekommt das `<img>` (mit absoluter `src`) und liefert einen lokalen
     *   (relativen) Pfad zurück, oder `null`, um die Original-URL zu behalten. Wird nur für Bilder
     *   aufgerufen, die tatsächlich im exportierten Inhalt landen.
     */
    fun convert(
        document: Document,
        contentSelector: String?,
        imageRewriter: ((Element) -> String?)? = null,
    ): Result {
        val title = document.title().ifBlank {
            document.selectFirst("h1")?.text().orEmpty()
        }.trim()

        document.outputSettings().prettyPrint(false)
        absolutizeUrls(document)
        document.select(ALWAYS_REMOVE).remove()
        normalizeCodeBlocks(document)

        val selector = contentSelector?.trim().orEmpty()
        val selected: List<Element> = if (selector.isNotEmpty()) {
            try {
                document.select(selector).toList()
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        val selectorMatched = selector.isEmpty() || selected.isNotEmpty()
        val contentRoots: List<Element> = selected.ifEmpty {
            // Ohne (passenden) Selektor: typische Layout-Elemente entfernen.
            document.body().also { it.select(LAYOUT_REMOVE).remove() }.let(::listOf)
        }
        if (imageRewriter != null) {
            contentRoots.flatMap { it.select("img[src]") }.forEach { img ->
                imageRewriter(img)?.let { local ->
                    img.attr("src", local)
                    img.removeAttr("srcset")
                }
            }
        }
        val html = if (selected.isNotEmpty()) {
            selected.joinToString("\n") { it.outerHtml() }
        } else {
            contentRoots.first().html()
        }

        val markdown = postProcess(converter.convert(html))
        return Result(title = title, markdown = markdown, selectorMatched = selectorMatched)
    }

    private fun absolutizeUrls(doc: Document) {
        doc.select("a[href]").forEach { a ->
            val abs = a.absUrl("href")
            if (abs.isNotEmpty()) a.attr("href", abs)
        }
        doc.select("img").forEach { img ->
            // Lazy-Loading: echte URL steckt oft in data-src, src ist nur ein Platzhalter.
            val src = img.attr("src")
            if (src.isBlank() || src.startsWith("data:")) {
                LAZY_SRC_ATTRS.firstOrNull { img.hasAttr(it) && img.attr(it).isNotBlank() }
                    ?.let { img.attr("src", img.attr(it)) }
            }
            val abs = img.absUrl("src")
            if (abs.isNotEmpty()) img.attr("src", abs)
        }
    }

    /**
     * Vereinheitlicht `<pre>`-Blöcke zu `<pre><code class="language-x">…</code></pre>`, damit Flexmark
     * Fenced-Code-Blöcke (```` ```x ````) statt eingerücktem Code erzeugt. Syntax-Highlighting-Spans
     * (Pygments, highlight.js, MediaWiki GeSHi, …) werden dabei zu reinem Text.
     */
    private fun normalizeCodeBlocks(doc: Document) {
        doc.select("pre").forEach { pre ->
            val code = pre.selectFirst("code")
            val classes = sequenceOf(pre, code, pre.parent(), pre.parent()?.parent())
                .filterNotNull()
                .flatMap { it.classNames().asSequence() }
            val lang = classes.firstNotNullOfOrNull { cls ->
                LANG_CLASS.matchEntire(cls)?.groupValues?.get(1)
            }?.lowercase()?.takeIf { it !in NON_LANGUAGES } ?: "text"
            val text = (code ?: pre).wholeText().trimEnd('\n', ' ')
            pre.empty()
            pre.clearAttributes()
            pre.appendElement("code").addClass("language-$lang").text(text)
        }
    }

    private fun postProcess(md: String): String = md
        .replace("\r\n", "\n")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim() + "\n"

    companion object {
        private val LAZY_SRC_ATTRS = listOf("data-src", "data-lazy-src", "data-original")
        private val LANG_CLASS = Regex("""(?:language|lang|highlight|brush|source)-([A-Za-z0-9_+#.-]+)""")
        private val NON_LANGUAGES = setOf("source", "none", "plaintext", "default")
        private const val ALWAYS_REMOVE =
            "script, style, noscript, template, iframe, svg, canvas, form, button, input, select, textarea, " +
                "link, meta, [hidden], [aria-hidden=true], .mw-editsection, .noprint"
        private const val LAYOUT_REMOVE =
            "header, footer, nav, aside, [role=navigation], [role=banner], [role=contentinfo], " +
                ".sidebar, .navbar, .toc, #toc, .breadcrumb, .breadcrumbs, #mw-navigation, #mw-panel, #footer"
    }
}
