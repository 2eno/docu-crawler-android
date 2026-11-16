package dev.docucrawler.android.crawler

import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownConverterTest {
    private val html = """
        <html><head><title>Install Guide</title><script>evil()</script></head>
        <body>
          <nav><a href="/x">Navigation</a></nav>
          <main>
            <h1>Installation</h1>
            <p>Run <code>make</code> then see <a href="/docs/next">next</a>.</p>
            <ul><li>One</li><li>Two</li></ul>
            <table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>
            <pre><code>echo hi</code></pre>
            <div class="highlight-python"><pre><span class="k">def</span> f():
            <span>return</span> 1</pre></div>
          </main>
          <footer>Footer text</footer>
        </body></html>
    """.trimIndent()

    @Test fun `selector extracts main content and converts`() {
        val doc = Jsoup.parse(html, "https://x.org/docs/install")
        val r = MarkdownConverter().convert(doc, "main")
        println(r.markdown)
        assertTrue(r.selectorMatched)
        assertTrue(r.markdown.contains("# Installation"))
        assertTrue(r.markdown.contains("[next](https://x.org/docs/next)"))
        assertTrue(r.markdown.contains("`make`"))
        assertTrue(r.markdown.contains("| A | B |") || r.markdown.contains("|A|B|") || r.markdown.contains("| A |"))
        assertTrue(r.markdown.contains("```text\necho hi\n```"))
        assertTrue(r.markdown.contains("```python\ndef f():\n    return 1\n```"))
        assertFalse(r.markdown.contains("Navigation"))
        assertFalse(r.markdown.contains("Footer"))
        assertFalse(r.markdown.contains("evil"))
    }

    @Test fun `without selector layout elements are removed`() {
        val doc = Jsoup.parse(html, "https://x.org/docs/install")
        val r = MarkdownConverter().convert(doc, null)
        assertTrue(r.markdown.contains("# Installation"))
        assertFalse(r.markdown.contains("Navigation"))
        assertFalse(r.markdown.contains("Footer"))
    }

    @Test fun `missing selector falls back to body`() {
        val doc = Jsoup.parse(html, "https://x.org/docs/install")
        val r = MarkdownConverter().convert(doc, "#does-not-exist")
        assertFalse(r.selectorMatched)
        assertTrue(r.markdown.contains("Installation"))
    }
}
