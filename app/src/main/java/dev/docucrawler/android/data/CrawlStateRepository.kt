package dev.docucrawler.android.data

import dev.docucrawler.android.crawler.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

enum class CrawlStatus { IDLE, RUNNING, CANCELLING, FINISHED, CANCELLED, FAILED }

data class LogEntry(val id: Long, val time: String, val level: LogLevel, val message: String)

data class CrawlUiState(
    val status: CrawlStatus = CrawlStatus.IDLE,
    val projectName: String? = null,
    val startUrl: String? = null,
    val pagesSaved: Int = 0,
    val queueSize: Int = 0,
    val visited: Int = 0,
    val errors: Int = 0,
    val skipped: Int = 0,
    val imagesSaved: Int = 0,
    val downloadImages: Boolean = false,
    val currentUrl: String? = null,
    val currentDepth: Int = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val logs: List<LogEntry> = emptyList(),
) {
    val isActive get() = status == CrawlStatus.RUNNING || status == CrawlStatus.CANCELLING
}

/** Single Source of Truth für den Crawl-Fortschritt: Service schreibt, ViewModel liest. */
object CrawlStateRepository {
    private const val MAX_LOG_LINES = 600

    private val _state = MutableStateFlow(CrawlUiState())
    val state: StateFlow<CrawlUiState> = _state.asStateFlow()

    // Thread-sicher: mehrere Crawler-Worker loggen gleichzeitig.
    private val logCounter = AtomicLong()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun reset(projectName: String, startUrl: String, downloadImages: Boolean = false) {
        _state.value = CrawlUiState(
            status = CrawlStatus.RUNNING,
            downloadImages = downloadImages,
            projectName = projectName,
            startUrl = startUrl,
            startedAt = System.currentTimeMillis(),
        )
    }

    fun update(block: (CrawlUiState) -> CrawlUiState) = _state.update(block)

    fun log(level: LogLevel, message: String) {
        val entry = LogEntry(logCounter.getAndIncrement(), LocalTime.now().format(timeFormat), level, message)
        _state.update { s -> s.copy(logs = (s.logs + entry).takeLast(MAX_LOG_LINES)) }
    }
}
