package dev.docucrawler.android.export

import dev.docucrawler.android.crawler.ImageDownloader
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reine JVM-Logik für ZIP- und Combined-Markdown-Export (ohne Android-Abhängigkeit, testbar). */
object Exporter {

    const val COMBINED_FILE_NAME = "combined_wiki.md"

    /** Alle Markdown-Dateien eines Projekts, stabil sortiert (index.md jeweils zuerst). */
    fun markdownFiles(projectDir: File): List<File> =
        projectDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name != COMBINED_FILE_NAME }
            .sortedWith(compareBy<File> { it.relativeTo(projectDir).parent.orEmpty() }
                .thenBy { if (it.name == "index.md") 0 else 1 }
                .thenBy { it.name })
            .toList()

    /** Bild-Assets des Projekts (`assets/…`), falls beim Crawl heruntergeladen. */
    fun assetFiles(projectDir: File): List<File> =
        File(projectDir, ImageDownloader.ASSETS_DIR).listFiles { f -> f.isFile && !f.name.endsWith(".part") }
            .orEmpty().sortedBy { it.name }

    /** Seiten-relative Bild-Links (`../../assets/x.png`) auf den Projekt-Wurzelordner umbiegen. */
    private val RELATIVE_ASSET_LINK = Regex("""\]\((?:\.\./)+${ImageDownloader.ASSETS_DIR}/""")

    /**
     * Führt alle Markdown-Dateien zu einer Datei zusammen – mit Inhaltsverzeichnis,
     * Trennlinien und Dateipfad-Überschriften. Ideal als Kontext für Claude.
     */
    fun writeCombinedMarkdown(projectDir: File, target: File, projectName: String): File {
        val files = markdownFiles(projectDir)
        target.parentFile?.mkdirs()
        target.bufferedWriter().use { w ->
            w.write("# $projectName – Wiki-Export\n\n")
            w.write("> ${files.size} Seiten, zusammengeführt von DocuCrawler. ")
            w.write("Jede Seite beginnt mit `## FILE: <pfad>` und ist durch `---` getrennt.\n\n")
            w.write("## Inhaltsverzeichnis\n\n")
            files.forEach { f -> w.write("- `${f.relativeTo(projectDir).invariantSeparatorsPath}`\n") }
            files.forEach { f ->
                val rel = f.relativeTo(projectDir).invariantSeparatorsPath
                w.write("\n\n---\n\n## FILE: `$rel`\n\n")
                // combined_wiki.md liegt im Projekt-Wurzelordner → Asset-Links dort gültig machen.
                w.write(f.readText().trim().replace(RELATIVE_ASSET_LINK, "](${ImageDownloader.ASSETS_DIR}/"))
                w.write("\n")
            }
        }
        return target
    }

    /**
     * Packt den kompletten Projektordner (Markdown + `assets/`-Bilder) in eine ZIP-Datei. Optional wird zusätzlich
     * eine `combined_wiki.md` im Wurzelverzeichnis des ZIPs abgelegt.
     */
    fun writeZip(projectDir: File, target: File, projectName: String, includeCombined: Boolean): File {
        target.parentFile?.mkdirs()
        val root = projectDir.name
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            (markdownFiles(projectDir) + assetFiles(projectDir)).forEach { f ->
                zip.putNextEntry(ZipEntry("$root/${f.relativeTo(projectDir).invariantSeparatorsPath}").apply {
                    time = f.lastModified()
                })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            if (includeCombined) {
                val tmp = File.createTempFile("combined", ".md", target.parentFile)
                try {
                    writeCombinedMarkdown(projectDir, tmp, projectName)
                    zip.putNextEntry(ZipEntry("$root/$COMBINED_FILE_NAME"))
                    tmp.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                } finally {
                    tmp.delete()
                }
            }
        }
        return target
    }
}
