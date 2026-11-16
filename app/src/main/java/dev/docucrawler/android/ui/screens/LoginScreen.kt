package dev.docucrawler.android.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.docucrawler.android.crawler.HttpCredentials
import dev.docucrawler.android.data.SessionData
import dev.docucrawler.android.data.SessionRepository
import dev.docucrawler.android.ui.MainViewModel
import kotlinx.coroutines.launch

/** Offene HTTP-Auth-Anfrage (Basic/Digest) der WebView – kann mehrere wartende Requests bündeln. */
private class AuthRequest(
    val host: String,
    val realm: String?,
    val handlers: MutableList<HttpAuthHandler>,
    val previousAttemptFailed: Boolean,
) {
    val key get() = "$host|$realm"
}

/**
 * Eingebetteter Browser für den interaktiven Login (Formular, OAuth, SSO, 2FA).
 * Nach erfolgreichem Login übernimmt der Button die Cookies aus dem `CookieManager`
 * sowie den User-Agent für den Crawler.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(vm: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val startUrl = remember { vm.loginUrl() }
    val userAgent = remember { vm.userAgent() }

    var currentUrl by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<SessionData?>(null) }
    var authRequest by remember { mutableStateOf<AuthRequest?>(null) }
    var lastSubmittedAuth by remember { mutableStateOf<HttpCredentials?>(null) }

    val webView = remember {
        WebView(context).apply webView@{
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false) // OAuth-Popups im selben Fenster öffnen
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                userAgentString = userAgent
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@webView, true) // nötig für viele SSO-Flows
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    // Nur http(s) im WebView laden; intent://, mailto: etc. ignorieren.
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    currentUrl = url
                    canGoBack = view.canGoBack()
                }

                /**
                 * HTTP-Authentifizierung (`401` + `WWW-Authenticate`, z. B. `.htaccess`). Ohne diesen
                 * Callback bricht die WebView die Anmeldung kommentarlos ab – Browser zeigen hier
                 * einen eigenen Dialog, den wir in Compose nachbauen.
                 */
                override fun onReceivedHttpAuthRequest(
                    view: WebView, handler: HttpAuthHandler, host: String, realm: String?,
                ) {
                    val pending = authRequest
                    if (pending != null && pending.host == host && pending.realm == realm) {
                        pending.handlers += handler // weiterer Request für denselben Bereich
                        return
                    }
                    pending?.handlers?.forEach { it.cancel() }
                    val last = lastSubmittedAuth
                    authRequest = AuthRequest(
                        host = host,
                        realm = realm,
                        handlers = mutableListOf(handler),
                        // Erneute Anfrage für bereits beantworteten Bereich ⇒ Zugangsdaten falsch.
                        previousAttemptFailed = last != null && last.host == host && last.realm == realm,
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    pageTitle = view.title.orEmpty()
                    canGoBack = view.canGoBack()
                    CookieManager.getInstance().flush()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    pageTitle = title.orEmpty()
                }
            }
            loadUrl(startUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            authRequest?.handlers?.forEach { it.cancel() }
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler(enabled = canGoBack) { webView.goBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                    },
                    title = {
                        Column {
                            Text(
                                pageTitle.ifBlank { "Login" },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                currentUrl,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { webView.goBack() }, enabled = canGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                        IconButton(onClick = { webView.reload() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Neu laden")
                        }
                    },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Melde dich wie gewohnt an (inkl. SSO/2FA). Danach Session übernehmen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            val session = vm.captureSession(webView.url ?: currentUrl, webView.settings.userAgentString)
                            if (session == null) {
                                scope.launch { snackbar.showSnackbar("Keine Cookies/Zugangsdaten gefunden – bist du eingeloggt?") }
                            } else {
                                captured = session
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = null)
                        Text("  Login abschließen & Session übernehmen")
                    }
                }
            }
        },
    ) { padding ->
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        )
    }

    captured?.let { session ->
        AlertDialog(
            onDismissRequest = { captured = null; onDone() },
            icon = { Icon(Icons.Filled.Cookie, contentDescription = null) },
            title = { Text("Session übernommen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    session.httpAuth?.let {
                        Text("HTTP-Login für ${it.host} als „${it.username}“" + (it.realm?.let { r -> " (Bereich „$r“)" } ?: ""))
                    }
                    if (session.cookieNames.isNotEmpty()) {
                        Text("${session.cookieNames.size} Cookies für ${session.host}:")
                        Text(
                            session.cookieNames.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Der Crawler sendet diese Anmeldedaten und denselben User-Agent bei jedem Request mit.")
                }
            },
            confirmButton = { TextButton(onClick = { captured = null; onDone() }) { Text("Weiter") } },
        )
    }

    authRequest?.let { req ->
        HttpAuthDialog(
            request = req,
            initialUsername = lastSubmittedAuth?.takeIf { it.host == req.host }?.username.orEmpty(),
            onSubmit = { user, pass ->
                val creds = HttpCredentials(req.host, req.realm, user, pass)
                lastSubmittedAuth = creds
                SessionRepository.rememberHttpAuth(creds)
                authRequest = null
                req.handlers.forEach { it.proceed(user, pass) }
            },
            onCancel = {
                authRequest = null
                req.handlers.forEach { it.cancel() }
            },
        )
    }
}

@Composable
private fun HttpAuthDialog(
    request: AuthRequest,
    initialUsername: String,
    onSubmit: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var username by remember(request.key) { mutableStateOf(initialUsername) }
    var password by remember(request.key) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val canSubmit = username.isNotEmpty()

    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.Key, contentDescription = null) },
        title = { Text("Anmeldung erforderlich") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    buildString {
                        append(request.host).append(" verlangt Benutzername und Passwort.")
                        request.realm?.takeIf { it.isNotBlank() }?.let { append(" Bereich: „").append(it).append("“") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (request.previousAttemptFailed) {
                    Text(
                        "Anmeldung fehlgeschlagen – bitte Groß-/Kleinschreibung prüfen.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Benutzername") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Passwort") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Passwort verbergen" else "Passwort anzeigen",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(username, password) }, enabled = canSubmit) { Text("Anmelden") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
    )
}
