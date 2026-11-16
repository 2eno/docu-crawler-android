package dev.docucrawler.android.crawler

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

class WikiCrawlerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val requested = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    private fun page(title: String, vararg links: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(
            "<html><head><title>$title</title></head><body><nav>NAV</nav><main><h2>$title</h2><p>Text of $title</p>" +
                links.joinToString("") { "<a href=\"$it\">$it</a>" } + "</main></body></html>"
        )

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request
                if (request.path == "/login") return page("Login")
                if (request.getHeader("Cookie")?.contains("session=abc") != true) {
                    return MockResponse().setResponseCode(302).setHeader("Location", "/login")
                }
                return when (request.path) {
                    "/docs/" -> page("Home", "guide/intro", "guide/setup#part", "/docs/api/", "/blog/post", "logout", "img.png", "https://external.org/x")
                    "/docs/guide/intro" -> page("Intro", "../", "setup", "deep/one")
                    "/docs/guide/setup" -> page("Setup")
                    "/docs/api/" -> page("API", "/docs/api/v1")
                    "/docs/api/v1" -> page("API v1")
                    "/docs/guide/deep/one" -> page("Deep", "two")
                    "/docs/guide/deep/two" -> page("Too deep")
                    "/docs/logout" -> error("logout must never be requested")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun crawler(depth: Int, cookie: String?, selector: String? = "main", logs: MutableList<String> = mutableListOf()) =
        WikiCrawler(
            config = CrawlConfig(
                startUrl = server.url("/docs/").toString(),
                projectName = "test",
                maxDepth = depth,
                delayMs = 0,
                contentSelector = selector,
            ),
            outputDir = tmp.root.resolve("out"),
            cookieProvider = StaticCookieProvider(cookie),
            userAgent = "TestUA/1.0",
            listener = object : WikiCrawler.Listener {
                override fun onProgress(progress: WikiCrawler.CrawlProgress) = Unit
                override fun onLog(level: LogLevel, message: String) { logs += "$level $message" }
            },
        )

    @Test fun `bfs crawl with cookies respects depth scope and excludes`() = runTest {
        val summary = crawler(depth = 2, cookie = "session=abc; other=1").run()
        val out = tmp.root.resolve("out")
        val files = out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).invariantSeparatorsPath }.sorted().toList()
        println(files)
        assertEquals(
            listOf(
                "docs/api/index.md", "docs/api/v1.md", "docs/guide/deep/one.md",
                "docs/guide/intro.md", "docs/guide/setup.md", "docs/index.md",
            ),
            files,
        )
        assertEquals(6, summary.pagesSaved)
        // Cookie + User-Agent bei jedem Request
        assertTrue(requested.all { it.getHeader("Cookie") == "session=abc; other=1" && it.getHeader("User-Agent") == "TestUA/1.0" })
        // Kein Request außerhalb des Bereichs, kein Logout, keine Binärdatei
        assertFalse(requested.any { it.path!!.startsWith("/blog") || it.path!!.contains("logout") || it.path!!.endsWith(".png") })
        // BFS-Reihenfolge: Startseite zuerst, dann Tiefe 1 vor Tiefe 2
        assertEquals("/docs/", requested.first().path)
        assertTrue(requested.indexOfFirst { it.path == "/docs/api/" } < requested.indexOfFirst { it.path == "/docs/guide/deep/one" })

        val intro = out.resolve("docs/guide/intro.md").readText()
        println(intro)
        assertTrue(intro.startsWith("---\ntitle: \"Intro\"\nsource: "))
        assertTrue(intro.contains("Text of Intro"))
        assertFalse(intro.contains("NAV"))
    }

    @Test fun `depth zero crawls only start page`() = runTest {
        val summary = crawler(depth = 0, cookie = "session=abc").run()
        assertEquals(1, summary.pagesSaved)
        assertEquals(1, requested.size)
    }

    @Test fun `missing session redirects to login and is reported`() = runTest {
        val logs = mutableListOf<String>()
        val summary = crawler(depth = 2, cookie = null, logs = logs).run()
        assertEquals(0, summary.pagesSaved)
        assertTrue(logs.any { it.contains("Keine Session-Cookies") })
        assertTrue(logs.any { it.contains("Weiterleitung aus dem Bereich") })
    }
}
