package dev.docucrawler.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.docucrawler.android.crawler.CrawlConfig
import dev.docucrawler.android.crawler.UrlUtils
import dev.docucrawler.android.data.CrawlStateRepository
import dev.docucrawler.android.data.CrawlUiState
import dev.docucrawler.android.data.SessionData
import dev.docucrawler.android.data.SessionRepository
import dev.docucrawler.android.data.SettingsStore
import dev.docucrawler.android.export.CrawlProject
import dev.docucrawler.android.export.Exporter
import dev.docucrawler.android.export.ProjectStorage
import dev.docucrawler.android.export.ShareHelper
import dev.docucrawler.android.service.WikiCrawlerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

data class SetupForm(
    val startUrl: String = "",
    val projectName: String = "",
    val projectNameEdited: Boolean = false,
    val maxDepth: String = "3",
    val delayMs: String = "500",
    val concurrency: Int = 4,
    val selector: String = "",
    val restrictToPathPrefix: Boolean = true,
    val maxPages: String = "0",
    val excludes: String = CrawlConfig.DEFAULT_EXCLUDES.joinToString("\n"),
) {
    val urlError: String?
        get() = when {
            startUrl.isBlank() -> null
            startUrl.trim().toHttpUrlOrNull() == null -> "Bitte eine gültige http(s)-URL eingeben"
            else -> null
        }
    val isValid: Boolean
        get() = startUrl.trim().toHttpUrlOrNull() != null &&
            projectName.isNotBlank() &&
            maxDepth.toIntOrNull()?.let { it >= 0 } == true &&
            delayMs.toLongOrNull()?.let { it >= 0 } == true

    fun toConfig() = CrawlConfig(
        startUrl = startUrl.trim(),
        projectName = UrlUtils.sanitizeSegment(projectName.trim()),
        maxDepth = maxDepth.toIntOrNull() ?: 3,
        delayMs = delayMs.toLongOrNull() ?: 500,
        concurrency = concurrency.coerceIn(1, 16),
        contentSelector = selector.trim().ifEmpty { null },
        restrictToPathPrefix = restrictToPathPrefix,
        maxPages = maxPages.toIntOrNull() ?: 0,
        excludePatterns = excludes.lines().map { it.trim() }.filter { it.isNotEmpty() },
    )

    companion object {
        fun from(c: CrawlConfig) = SetupForm(
            startUrl = c.startUrl,
            projectName = c.projectName,
            projectNameEdited = c.projectName != UrlUtils.suggestProjectName(c.startUrl),
            maxDepth = c.maxDepth.toString(),
            delayMs = c.delayMs.toString(),
            concurrency = c.concurrency,
            selector = c.contentSelector.orEmpty(),
            restrictToPathPrefix = c.restrictToPathPrefix,
            maxPages = c.maxPages.toString(),
            excludes = c.excludePatterns.joinToString("\n"),
        )
    }
}

data class ExportUiState(
    val busy: Boolean = false,
    val includeCombinedInZip: Boolean = true,
    val lastExport: File? = null,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    private val _form = MutableStateFlow(settings.load()?.let(SetupForm::from) ?: SetupForm())
    val form: StateFlow<SetupForm> = _form.asStateFlow()

    val session: StateFlow<SessionData?> = SessionRepository.session
    val crawlState: StateFlow<CrawlUiState> = CrawlStateRepository.state

    private val _projects = MutableStateFlow<List<CrawlProject>>(emptyList())
    val projects: StateFlow<List<CrawlProject>> = _projects.asStateFlow()

    private val _export = MutableStateFlow(ExportUiState())
    val export: StateFlow<ExportUiState> = _export.asStateFlow()

    init {
        refreshProjects()
    }

    // ---------- Setup ----------

    fun updateForm(block: (SetupForm) -> SetupForm) = _form.update { old ->
        val new = block(old)
        if (!new.projectNameEdited && new.startUrl != old.startUrl) {
            new.copy(projectName = UrlUtils.suggestProjectName(new.startUrl))
        } else new
    }

    /** Liefert die Start-URL (inkl. Schema), die im Login-WebView geöffnet wird. */
    fun loginUrl(): String = _form.value.startUrl.trim().let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
    }

    fun userAgent(): String = SessionRepository.defaultUserAgent(getApplication())

    fun captureSession(currentUrl: String?, userAgent: String): SessionData? =
        SessionRepository.captureFromWebView(loginUrl(), currentUrl, userAgent)

    fun clearSession() = SessionRepository.clear()

    fun startCrawl(): Boolean {
        val f = _form.value
        if (!f.isValid || crawlState.value.isActive) return false
        val config = f.toConfig()
        settings.save(config)
        WikiCrawlerService.start(getApplication(), config)
        return true
    }

    fun cancelCrawl() = WikiCrawlerService.stop(getApplication())

    // ---------- Export ----------

    fun refreshProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            _projects.value = ProjectStorage.listProjects(getApplication())
        }
    }

    fun setIncludeCombined(value: Boolean) = _export.update { it.copy(includeCombinedInZip = value) }

    fun exportZip(project: CrawlProject) = runExport {
        val target = File(ShareHelper.exportDir(getApplication()), "${project.name}.zip")
        Exporter.writeZip(project.dir, target, project.name, _export.value.includeCombinedInZip) to "application/zip"
    }

    fun exportCombined(project: CrawlProject) = runExport {
        val target = File(ShareHelper.exportDir(getApplication()), "${project.name}_${Exporter.COMBINED_FILE_NAME}")
        Exporter.writeCombinedMarkdown(project.dir, target, project.name) to "text/markdown"
    }

    fun deleteProject(project: CrawlProject) {
        viewModelScope.launch(Dispatchers.IO) {
            ProjectStorage.delete(project)
            _projects.value = ProjectStorage.listProjects(getApplication())
        }
    }

    fun shareAgain() {
        val file = _export.value.lastExport ?: return
        ShareHelper.share(getApplication(), file, if (file.extension == "zip") "application/zip" else "text/markdown")
    }

    fun consumeMessage() = _export.update { it.copy(message = null) }

    private fun runExport(block: suspend () -> Pair<File, String>) {
        if (_export.value.busy) return
        _export.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val (file, mime) = withContext(Dispatchers.IO) { block() }
                _export.update {
                    it.copy(busy = false, lastExport = file, message = "${file.name} erstellt (${formatBytes(file.length())})")
                }
                // Nach Fertigstellung direkt das Share-Sheet öffnen.
                ShareHelper.share(getApplication(), file, mime)
            } catch (e: Exception) {
                _export.update { it.copy(busy = false, message = "Export fehlgeschlagen: ${e.message}") }
            }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
