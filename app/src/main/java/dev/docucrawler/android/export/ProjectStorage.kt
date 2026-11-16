package dev.docucrawler.android.export

import android.content.Context
import dev.docucrawler.android.crawler.UrlUtils
import java.io.File

data class CrawlProject(
    val name: String,
    val dir: File,
    val fileCount: Int,
    val totalBytes: Long,
    val lastModified: Long,
)

/** Ablage der Crawls unter `filesDir/crawls/<projektname>/`. */
object ProjectStorage {

    fun crawlsRoot(context: Context): File = File(context.filesDir, "crawls").apply { mkdirs() }

    fun projectDir(context: Context, projectName: String): File =
        File(crawlsRoot(context), UrlUtils.sanitizeSegment(projectName)).apply { mkdirs() }

    fun listProjects(context: Context): List<CrawlProject> =
        crawlsRoot(context).listFiles { f -> f.isDirectory }.orEmpty()
            .map { dir ->
                val files = dir.walkTopDown().filter { it.isFile && it.extension == "md" }.toList()
                CrawlProject(
                    name = dir.name,
                    dir = dir,
                    fileCount = files.size,
                    totalBytes = files.sumOf { it.length() },
                    lastModified = files.maxOfOrNull { it.lastModified() } ?: dir.lastModified(),
                )
            }
            .sortedByDescending { it.lastModified }

    fun delete(project: CrawlProject) = project.dir.deleteRecursively()
}
