package dev.docucrawler.android.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.docucrawler.android.ui.MainViewModel
import java.text.DateFormat
import kotlin.math.roundToInt
import java.util.Date

private val SELECTOR_PRESETS = listOf("main", "article", "#mw-content-text", ".markdown-body", "#content")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: MainViewModel, onOpenLogin: () -> Unit, onCrawlStarted: () -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val crawl by vm.crawlState.collectAsStateWithLifecycle()
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Crawl startet unabhängig vom Ergebnis; ohne Berechtigung fehlt nur die Benachrichtigung.
        if (vm.startCrawl()) onCrawlStarted()
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (vm.startCrawl()) onCrawlStarted()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("DocuCrawler") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Crawlt ganze Wikis & Dokus rekursiv, wandelt sie in Markdown um und exportiert sie als ZIP für Claude.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------- Ziel ----------
            SectionCard(title = "1 · Ziel") {
                OutlinedTextField(
                    value = form.startUrl,
                    onValueChange = { v -> vm.updateForm { it.copy(startUrl = v) } },
                    label = { Text("Start-URL") },
                    placeholder = { Text("https://wiki.example.com/docs/") },
                    singleLine = true,
                    isError = form.urlError != null,
                    supportingText = form.urlError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.projectName,
                    onValueChange = { v -> vm.updateForm { it.copy(projectName = v, projectNameEdited = true) } },
                    label = { Text("Projektname") },
                    supportingText = { Text("Ordner: filesDir/crawls/${form.projectName.ifBlank { "…" }}/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---------- Login ----------
            SectionCard(title = "2 · Login (optional)") {
                val s = session
                if (s != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            val parts = buildList {
                                if (s.cookieNames.isNotEmpty()) add("${s.cookieNames.size} Cookies")
                                s.httpAuth?.let { add("HTTP-Login '${it.username}'") }
                            }
                            Text("Session aktiv · ${parts.joinToString(" + ")}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${s.host} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.capturedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = vm::clearSession) { Text("Abmelden") }
                    }
                } else {
                    Text(
                        "Für geschützte Wikis: im eingebetteten Browser einloggen (Formular, HTTP-Login, SSO, OAuth, 2FA). " +
                            "Cookies bzw. Zugangsdaten werden anschließend vom Crawler verwendet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = onOpenLogin,
                    enabled = form.urlError == null && form.startUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(if (s == null) Icons.AutoMirrored.Filled.Login else Icons.Filled.LockOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (s == null) "Im Browser einloggen" else "Erneut einloggen")
                }
            }

            // ---------- Parameter ----------
            SectionCard(title = "3 · Crawl-Parameter") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.maxDepth,
                        onValueChange = { v -> vm.updateForm { it.copy(maxDepth = v.filter(Char::isDigit).take(3)) } },
                        label = { Text("Max. Tiefe") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.delayMs,
                        onValueChange = { v -> vm.updateForm { it.copy(delayMs = v.filter(Char::isDigit).take(6)) } },
                        label = { Text("Delay (ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Parallele Verbindungen", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${form.concurrency}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = form.concurrency.toFloat(),
                        onValueChange = { v -> vm.updateForm { it.copy(concurrency = v.roundToInt()) } },
                        valueRange = 1f..16f,
                        steps = 14,
                    )
                    val delay = form.delayMs.toLongOrNull() ?: 0
                    val est = form.concurrency * 1000.0 / (delay + 400)
                    Text(
                        "Delay gilt pro Verbindung · ca. %.1f Seiten/s (bei ~400 ms Antwortzeit). ".format(est) +
                            "Höhere Werte belasten den Server stärker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = form.selector,
                    onValueChange = { v -> vm.updateForm { it.copy(selector = v) } },
                    label = { Text("CSS-Selektor für Inhalt (optional)") },
                    placeholder = { Text("z. B. main, article, #mw-content-text") },
                    supportingText = { Text("Leer = ganze Seite ohne Header/Nav/Footer") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScrollRow(),
                ) {
                    SELECTOR_PRESETS.forEach { preset ->
                        AssistChip(
                            onClick = { vm.updateForm { it.copy(selector = preset) } },
                            label = { Text(preset) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bilder herunterladen", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Bilder im Inhalt landen in assets/ und werden lokal verlinkt (nur im ZIP).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = form.downloadImages,
                        onCheckedChange = { v -> vm.updateForm { it.copy(downloadImages = v) } },
                    )
                }

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Icon(if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    Text(" Erweitert")
                }
                AnimatedVisibility(showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Nur unterhalb des Start-Pfads", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Aus: ganze Domain crawlen",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = form.restrictToPathPrefix,
                                onCheckedChange = { v -> vm.updateForm { it.copy(restrictToPathPrefix = v) } },
                            )
                        }
                        if (form.downloadImages) {
                            OutlinedTextField(
                                value = form.maxImageMb,
                                onValueChange = { v -> vm.updateForm { it.copy(maxImageMb = v.filter(Char::isDigit).take(3)) } },
                                label = { Text("Max. Bildgröße (MB)") },
                                supportingText = { Text("Größere Bilder werden übersprungen") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedTextField(
                            value = form.maxPages,
                            onValueChange = { v -> vm.updateForm { it.copy(maxPages = v.filter(Char::isDigit).take(6)) } },
                            label = { Text("Max. Seiten (0 = unbegrenzt)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = form.excludes,
                            onValueChange = { v -> vm.updateForm { it.copy(excludes = v) } },
                            label = { Text("Ausschluss-Muster (eins pro Zeile)") },
                            supportingText = { Text("Wichtig: Logout-Links ausschließen, sonst endet die Session!") },
                            minLines = 3,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Button(
                onClick = ::start,
                enabled = form.isValid && !crawl.isActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (crawl.isActive) "Crawl läuft bereits…" else "Crawl starten")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Modifier.horizontalScrollRow(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))
