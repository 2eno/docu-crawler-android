package dev.docucrawler.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.docucrawler.android.export.CrawlProject
import dev.docucrawler.android.ui.MainViewModel
import dev.docucrawler.android.ui.formatBytes
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(vm: MainViewModel) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val crawl by vm.crawlState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var toDelete by remember { mutableStateOf<CrawlProject?>(null) }

    LaunchedEffect(Unit) { vm.refreshProjects() }
    LaunchedEffect(export.message) {
        export.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Export für Claude") },
                actions = {
                    IconButton(onClick = vm::refreshProjects) { Icon(Icons.Filled.Refresh, "Aktualisieren") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("combined_wiki.md ins ZIP aufnehmen", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Alle Seiten in einer Datei mit Trennlinien & Pfad-Überschriften – ideal für Claude.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = export.includeCombinedInZip, onCheckedChange = vm::setIncludeCombined)
                    }
                }
            }

            export.lastExport?.let { file ->
                item {
                    FilledTonalButton(onClick = vm::shareAgain, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Erneut teilen: ${file.name}")
                    }
                }
            }

            if (projects.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Noch keine Crawls vorhanden.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(projects, key = { it.name }) { project ->
                val running = crawl.isActive && crawl.projectName == project.name
                ProjectCard(
                    project = project,
                    busy = export.busy,
                    running = running,
                    onZip = { vm.exportZip(project) },
                    onCombined = { vm.exportCombined(project) },
                    onDelete = { toDelete = project },
                )
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Projekt löschen?") },
            text = { Text("„${p.name}“ mit ${p.fileCount} Dateien wird endgültig entfernt.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteProject(p); toDelete = null }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ProjectCard(
    project: CrawlProject,
    busy: Boolean,
    running: Boolean,
    onZip: () -> Unit,
    onCombined: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${project.fileCount} Seiten · " +
                            (if (project.imageCount > 0) "${project.imageCount} Bilder · " else "") +
                            "${formatBytes(project.totalBytes)} · " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(project.lastModified)) +
                            if (running) " · Crawl läuft" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, enabled = !running) { Icon(Icons.Filled.Delete, "Löschen") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onZip, enabled = !busy && project.fileCount > 0, modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.FolderZip, null)
                    Spacer(Modifier.width(6.dp))
                    Text("ZIP")
                }
                FilledTonalButton(onClick = onCombined, enabled = !busy && project.fileCount > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.Article, null)
                    Spacer(Modifier.width(6.dp))
                    Text("combined.md")
                }
            }
        }
    }
}
