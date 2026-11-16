package dev.docucrawler.android.crawler

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageCandidatesTest {
    private fun img(html: String) = Jsoup.parse(html, "https://www.follow.de/interna/followpedia/index.php/Seite")
        .selectFirst("img")!!

    @Test fun `mediawiki thumbnail resolves to original, description page is ignored`() {
        val el = img(
            """<a href="/interna/followpedia/index.php/Datei:Foto_1.jpg" class="image">
               <img src="/interna/followpedia/images/thumb/a/ab/Foto_1.jpg/220px-Foto_1.jpg"
                    srcset="/interna/followpedia/images/thumb/a/ab/Foto_1.jpg/330px-Foto_1.jpg 1.5x, /interna/followpedia/images/thumb/a/ab/Foto_1.jpg/440px-Foto_1.jpg 2x"></a>"""
        )
        assertEquals(
            listOf(
                "https://www.follow.de/interna/followpedia/images/a/ab/Foto_1.jpg",
                "https://www.follow.de/interna/followpedia/images/thumb/a/ab/Foto_1.jpg/440px-Foto_1.jpg",
                "https://www.follow.de/interna/followpedia/images/thumb/a/ab/Foto_1.jpg/220px-Foto_1.jpg",
            ),
            ImageCandidates.resolve(el, preferOriginal = true),
        )
    }

    @Test fun `svg thumbnails resolve to the svg original`() {
        assertEquals(
            "https://x.org/w/images/c/cd/Plan.svg",
            ImageCandidates.mediaWikiOriginal("https://x.org/w/images/thumb/c/cd/Plan.svg/300px-Plan.svg.png"),
        )
        assertNull(ImageCandidates.mediaWikiOriginal("https://x.org/w/images/c/cd/Plan.png"))
    }

    @Test fun `linked image file and srcset widths`() {
        val el = img("""<a href="/media/gross.jpg"><img src="/media/klein.jpg" srcset="/media/m.jpg 600w, /media/l.jpg 1200w, /media/s.jpg 300w"></a>""")
        assertEquals(
            listOf("https://www.follow.de/media/gross.jpg", "https://www.follow.de/media/l.jpg", "https://www.follow.de/media/klein.jpg"),
            ImageCandidates.resolve(el, preferOriginal = true),
        )
    }

    @Test fun `without preferOriginal only the embedded src is used`() {
        val el = img("""<a href="/media/gross.jpg"><img src="/media/klein.jpg"></a>""")
        assertEquals(listOf("https://www.follow.de/media/klein.jpg"), ImageCandidates.resolve(el, preferOriginal = false))
    }
}
