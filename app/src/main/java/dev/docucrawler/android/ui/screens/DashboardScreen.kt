package dev.docucrawler.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.docucrawler.android.crawler.LogLevel
import dev.docucrawler.android.data.CrawlStatus
import dev.docucrawler.android.data.LogEntry
import dev.docucrawler.android.ui.MainViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: MainViewModel, onOpenExport: () -> Unit) {
    val state by vm.crawlState.collectAsStateWithLifecycle()

    // Laufzeit-Anzeige, tickt jede Sekunde während des Crawls.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isActive) {
        now = System.currentTimeMillis()
        while (state.isActive) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = state.startedAt?.let { ((state.finishedAt ?: now) - it) / 1000 } ?: 0

    LaunchedEffect(state.status) {
        if (state.status == CrawlStatus.FINISHED || state.status == CrawlStatus.CANCELLED) vm.refreshProjects()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Crawler")
                    state.projectName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            })
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBanner(state.status, elapsed)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Gespeichert", state.pagesSaved.toString(), Modifier.weight(1f))
                StatTile("Warteschlange", state.queueSize.toString(), Modifier.weight(1f))
                StatTile("Fehler", state.errors.toString(), Modifier.weight(1f), isError = state.errors > 0)
                StatTile("Übersprungen", state.skipped.toString(), Modifier.weight(1f))
            }

            if (state.isActive) {
                val total = state.pagesSaved + state.queueSize + state.errors + state.skipped
                val done = state.pagesSaved + state.errors + state.skipped
                if (total > 1) {
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp).fillMaxWidth()) {
                        Text("Aktuell (Tiefe ${state.currentDepth})", style = MaterialTheme.typography.labelMedium)
                        Text(
                            state.currentUrl ?: "…",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Text("Log", style = MaterialTheme.typography.titleSmall)
            LogView(state.logs, Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                if (state.isActive) {
                    Button(
                        onClick = vm::cancelCrawl,
                        enabled = state.status == CrawlStatus.RUNNING,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.status == CrawlStatus.CANCELLING) "Wird abgebrochen…" else "Abbrechen")
                    }
                } else if (state.status != CrawlStatus.IDLE) {
                    Button(onClick = onOpenExport, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Archive, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Zum Export")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(status: CrawlStatus, elapsedSec: Long) {
    val (label, color) = when (status) {
        CrawlStatus.IDLE -> "Kein Crawl aktiv – starte im Setup." to MaterialTheme.colorScheme.surfaceVariant
        CrawlStatus.RUNNING -> "Läuft" to MaterialTheme.colorScheme.primaryContainer
        CrawlStatus.CANCELLING -> "Wird abgebrochen…" to MaterialTheme.colorScheme.tertiaryContainer
        CrawlStatus.FINISHED -> "Abgeschlossen" to MaterialTheme.colorScheme.primaryContainer
        CrawlStatus.CANCELLED -> "Abgebrochen" to MaterialTheme.colorScheme.tertiaryContainer
        CrawlStatus.FAILED -> "Fehlgeschlagen" to MaterialTheme.colorScheme.errorContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (status != CrawlStatus.IDLE) {
                Text("%d:%02d".format(elapsedSec / 60, elapsedSec % 60), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, isError: Boolean = false) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LogView(logs: List<LogEntry>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        if (logs.isEmpty()) {
            Text("Noch keine Ausgabe.", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        LazyColumn(state = listState) {
            items(logs, key = { it.id }) { entry ->
                Text(
                    "${entry.time}  ${entry.message}",
                    color = when (entry.level) {
                        LogLevel.DEBUG -> Color(0xFF8A8A8A)
                        LogLevel.INFO -> Color(0xFFDDDDDD)
                        LogLevel.SUCCESS -> Color(0xFF7FD67F)
                        LogLevel.WARN -> Color(0xFFFFC66D)
                        LogLevel.ERROR -> Color(0xFFFF6B68)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
