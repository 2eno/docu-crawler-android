package dev.docucrawler.android.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class ExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project() = tmp.newFolder("proj").also { dir ->
        dir.resolve("docs/guide").mkdirs()
        dir.resolve("docs/index.md").writeText("# Home\n")
        dir.resolve("docs/guide/setup.md").writeText("# Setup\n")
        dir.resolve("docs/guide/index.md").writeText("# Guide\n")
    }

    @Test fun `zip contains all files plus combined`() {
        val dir = project()
        val zipFile = Exporter.writeZip(dir, tmp.root.resolve("e/proj.zip"), "proj", includeCombined = true)
        val names = ZipFile(zipFile).use { z -> z.entries().toList().map { it.name } }
        assertEquals(
            listOf("proj/docs/index.md", "proj/docs/guide/index.md", "proj/docs/guide/setup.md", "proj/combined_wiki.md"),
            names,
        )
    }

    @Test fun `combined markdown has separators and path headings`() {
        val dir = project()
        val out = Exporter.writeCombinedMarkdown(dir, tmp.root.resolve("c.md"), "proj").readText()
        println(out)
        assertTrue(out.contains("\n---\n\n## FILE: `docs/guide/setup.md`\n\n# Setup"))
        assertTrue(out.indexOf("FILE: `docs/index.md`") < out.indexOf("FILE: `docs/guide/index.md`"))
    }

    @Test fun `zip includes assets and combined links point to root assets`() {
        val dir = project()
        dir.resolve("assets").mkdirs()
        dir.resolve("assets/foto-1234abcd.png").writeBytes(ByteArray(10))
        dir.resolve("docs/guide/setup.md").writeText("# Setup\n\n![Foto](../../assets/foto-1234abcd.png)\n")

        val names = ZipFile(Exporter.writeZip(dir, tmp.root.resolve("e/p.zip"), "proj", includeCombined = true))
            .use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.contains("proj/assets/foto-1234abcd.png"))

        val combined = Exporter.writeCombinedMarkdown(dir, tmp.root.resolve("c.md"), "proj").readText()
        assertTrue(combined.contains("![Foto](assets/foto-1234abcd.png)"))
    }
}
