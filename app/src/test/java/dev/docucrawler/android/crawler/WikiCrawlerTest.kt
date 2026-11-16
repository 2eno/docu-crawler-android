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

    private fun crawler(
        depth: Int,
        cookie: String?,
        selector: String? = "main",
        logs: MutableList<String> = mutableListOf(),
        auth: HttpCredentials? = null,
        concurrency: Int = 4,
        maxPages: Int = 0,
        downloadImages: Boolean = false,
    ) =
        WikiCrawler(
            config = CrawlConfig(
                startUrl = server.url("/docs/").toString(),
                projectName = "test",
                maxDepth = depth,
                delayMs = 0,
                concurrency = concurrency,
                maxPages = maxPages,
                downloadImages = downloadImages,
                maxImageBytes = 1000,
                contentSelector = selector,
            ),
            outputDir = tmp.root.resolve("out"),
            cookieProvider = StaticCookieProvider(cookie),
            userAgent = "TestUA/1.0",
            listener = object : WikiCrawler.Listener {
                override fun onProgress(progress: WikiCrawler.CrawlProgress) = Unit
                override fun onLog(level: LogLevel, message: String) { logs += "$level $message" }
            },
            httpAuth = auth,
        )

    @Test fun `bfs crawl with cookies respects depth scope and excludes`() = runTest {
        // concurrency = 1: nur sequenziell ist die strikte BFS-Reihenfolge garantiert. Dass parallele
        // Crawls dasselbe Ergebnis liefern, prüft `parallel and sequential crawls produce identical output`.
        val summary = crawler(depth = 2, cookie = "session=abc; other=1", concurrency = 1).run()
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

    @Test fun `http basic auth is sent preemptively and only to the same host`() = runTest {
        val expected = okhttp3.Credentials.basic("Ingo", "Geheim-ä", Charsets.UTF_8)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request
                if (request.getHeader("Authorization") != expected) {
                    return MockResponse().setResponseCode(401)
                        .setHeader("WWW-Authenticate", "Basic realm=\"INTERNBEREICH\"")
                }
                return when (request.path) {
                    "/docs/" -> page("Home", "a")
                    "/docs/a" -> page("A")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val host = server.url("/").host
        val summary = crawler(depth = 1, cookie = null, auth = HttpCredentials(host, "INTERNBEREICH", "Ingo", "Geheim-ä")).run()
        assertEquals(2, summary.pagesSaved)
        assertTrue(requested.all { it.getHeader("Authorization") == expected })

        // Falsche Zugangsdaten ⇒ verständliche Fehlermeldung
        val logs = mutableListOf<String>()
        val failed = crawler(depth = 1, cookie = null, logs = logs, auth = HttpCredentials(host, null, "Ingo", "falsch")).run()
        assertEquals(0, failed.pagesSaved)
        assertTrue(logs.any { it.contains("Benutzername/Passwort falsch?") })

        // Andere Domain ⇒ kein Authorization-Header
        assertFalse(HttpCredentials("other.org", null, "u", "p").appliesTo(host))
        assertTrue(HttpCredentials("www.follow.de", null, "u", "p").appliesTo("follow.de"))
    }

    private fun savedFiles(): List<String> {
        val out = tmp.root.resolve("out")
        return out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).invariantSeparatorsPath }.sorted().toList()
    }

    @Test fun `parallel and sequential crawls produce identical output`() = runTest {
        crawler(depth = 3, cookie = "session=abc", concurrency = 1).run()
        val sequential = savedFiles()
        val sequentialRequests = requested.size
        tmp.root.resolve("out").deleteRecursively()
        requested.clear()

        val summary = crawler(depth = 3, cookie = "session=abc", concurrency = 8).run()
        assertEquals(sequential, savedFiles())
        assertEquals(7, summary.pagesSaved)
        // Keine doppelten Requests trotz paralleler Worker
        assertEquals(sequentialRequests, requested.size)
        assertEquals(requested.size, requested.map { it.path }.toSet().size)
    }

    @Test fun `workers really fetch in parallel`() = runTest {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maxActive = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    Thread.sleep(150)
                    val path = request.path!!
                    return if (path == "/docs/") page("Home", *Array(12) { "p$it" }) else page(path)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val t0 = System.nanoTime()
        val summary = crawler(depth = 1, cookie = null, concurrency = 6).run()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("13 Seiten mit 6 Workern in $ms ms, max. gleichzeitig: ${maxActive.get()}")
        assertEquals(13, summary.pagesSaved)
        assertTrue(maxActive.get() >= 4)
        assertTrue("zu langsam: $ms ms", ms < 13 * 150)
    }

    @Test fun `max pages limit holds with parallel workers`() = runTest {
        val summary = crawler(depth = 3, cookie = "session=abc", concurrency = 8, maxPages = 3).run()
        assertEquals(3, summary.pagesSaved)
    }

    @Test fun `images are downloaded once, filtered and linked relatively`() = runTest {
        val png = okio.Buffer().write(ByteArray(200) { it.toByte() })
        val auth = okhttp3.Credentials.basic("u", "p", Charsets.UTF_8)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request
                if (request.getHeader("Authorization") != auth) return MockResponse().setResponseCode(401)
                fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html").setBody(
                    "<html><head><title>T</title></head><body><header><img src='/skin/logo.png'></header><main>$body</main></body></html>"
                )
                return when (request.path) {
                    "/docs/" -> html("<a href='sub/page'>p</a><img src='/img/foto.png' alt='Foto'><img src='/img/huge.jpg'>")
                    "/docs/sub/page" -> html("<img src='data:image/gif;base64,R0' data-src='/img/foto.png' alt='Wieder'><img src='/img/fake.png'>")
                    "/img/foto.png", "/skin/logo.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody(png.copy())
                    "/img/huge.jpg" -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody(okio.Buffer().write(ByteArray(5000)))
                    "/img/fake.png" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login</html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val host = server.url("/").host
        val summary = crawler(
            depth = 1, cookie = null, concurrency = 4, downloadImages = true,
            auth = HttpCredentials(host, null, "u", "p"),
        ).run()

        assertEquals(2, summary.pagesSaved)
        assertEquals(1, summary.imagesSaved)
        val out = tmp.root.resolve("out")
        val assets = out.resolve("assets").listFiles()!!.map { it.name }
        assertEquals(1, assets.size)
        val foto = assets.single()
        assertTrue(foto, foto.matches(Regex("foto-[0-9a-f]{8}\\.png")))
        assertEquals(200L, out.resolve("assets/$foto").length())

        // Bild nur einmal geladen, Logo aus <header> nie angefragt
        assertEquals(1, requested.count { it.path == "/img/foto.png" })
        assertFalse(requested.any { it.path == "/skin/logo.png" })

        val index = out.resolve("docs/index.md").readText()
        val sub = out.resolve("docs/sub/page.md").readText()
        println(index); println(sub)
        assertTrue(index.contains("![Foto](../assets/$foto)"))
        assertTrue(sub.contains("![Wieder](../../assets/$foto)"))
        // Zu große / falsche Bilder behalten ihre Original-URL
        assertTrue(index.contains("/img/huge.jpg)"))
        assertTrue(sub.contains("/img/fake.png)"))
    }

    @Test fun `image downloading is off by default`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request
                return MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><body><main><p>x</p><img src='/img/a.png'></main></body></html>")
            }
        }
        val summary = crawler(depth = 0, cookie = null).run()
        assertEquals(0, summary.imagesSaved)
        assertEquals(listOf("/docs/"), requested.map { it.path })
        assertFalse(tmp.root.resolve("out/assets").exists())
    }
}
