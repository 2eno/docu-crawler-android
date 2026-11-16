package dev.docucrawler.android.crawler

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test fun `maps url paths to md files`() {
        assertEquals("docs/guide/install.md", UrlUtils.toRelativeFilePath("https://x.org/docs/guide/install".toHttpUrl()))
        assertEquals("docs/guide/index.md", UrlUtils.toRelativeFilePath("https://x.org/docs/guide/".toHttpUrl()))
        assertEquals("index.md", UrlUtils.toRelativeFilePath("https://x.org".toHttpUrl()))
        assertEquals("docs/page.md", UrlUtils.toRelativeFilePath("https://x.org/docs/page.html".toHttpUrl()))
        assertEquals("wiki/Über_uns.md", UrlUtils.toRelativeFilePath("https://x.org/wiki/%C3%9Cber%20uns".toHttpUrl()))
        assertEquals("index__title=Main_Page.md", UrlUtils.toRelativeFilePath("https://x.org/index.php?title=Main_Page".toHttpUrl()))
    }

    @Test fun `scope respects host and path prefix`() {
        val start = "https://wiki.x.org/docs/start".toHttpUrl()
        val prefix = UrlUtils.pathPrefixOf(start)
        assertEquals("/docs/", prefix)
        assertTrue(UrlUtils.isInScope("https://wiki.x.org/docs/a/b".toHttpUrl(), start, prefix))
        assertTrue(UrlUtils.isInScope("https://wiki.x.org/docs".toHttpUrl(), start, prefix))
        assertFalse(UrlUtils.isInScope("https://wiki.x.org/blog/a".toHttpUrl(), start, prefix))
        assertFalse(UrlUtils.isInScope("https://other.org/docs/a".toHttpUrl(), start, prefix))
        assertTrue(UrlUtils.isInScope("https://wiki.x.org/blog/a".toHttpUrl(), start, null))
    }

    @Test fun `normalize strips fragments`() {
        assertEquals("https://x.org/a?b=1", UrlUtils.normalize("https://X.org:443/a?b=1#frag"))
    }

    @Test fun `filters binaries and excludes`() {
        assertFalse(UrlUtils.looksLikeHtml("https://x.org/a/image.PNG".toHttpUrl()))
        assertTrue(UrlUtils.looksLikeHtml("https://x.org/a/page.html".toHttpUrl()))
        assertTrue(UrlUtils.looksLikeHtml("https://x.org/a/v1.2".toHttpUrl()))
        assertTrue(UrlUtils.matchesExclude("https://x.org/user/Logout", CrawlConfig.DEFAULT_EXCLUDES))
    }
}
