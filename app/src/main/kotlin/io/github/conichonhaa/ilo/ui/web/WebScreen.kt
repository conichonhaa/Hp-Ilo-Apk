package io.github.conichonhaa.ilo.ui.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conichonhaa.ilo.R
import io.github.conichonhaa.ilo.core.net.CertificatePinning
import io.github.conichonhaa.ilo.core.net.IloClient
import io.github.conichonhaa.ilo.data.ServerConfig
import io.github.conichonhaa.ilo.data.ServerRepository
import io.github.conichonhaa.ilo.ui.common.CertPrompt
import io.github.conichonhaa.ilo.ui.common.CertificateDialog
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The iLO web interface in a WebView. The iLO login page is shown normally, with the stored
 * credentials filled in; the certificate is checked against the pinned fingerprint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(repository: ServerRepository, serverId: String, onBack: () -> Unit) {
    val server by remember(serverId) {
        repository.servers.map { list -> list.firstOrNull { it.id == serverId } }
    }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var certPrompt by remember { mutableStateOf<CertPrompt?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var showLog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val baseUrl = server?.let { IloClient.normalizeAddress(it.address) }?.let { "https://$it/" }

    fun openExternally() {
        baseUrl ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(baseUrl))) }
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server?.displayName ?: stringResource(R.string.web_interface)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        loadError = null
                        webView?.reload()
                    }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                    IconButton(onClick = { showLog = !showLog }) {
                        Icon(Icons.Default.BugReport, stringResource(R.string.web_diagnostics))
                    }
                    IconButton(onClick = ::openExternally) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.web_open_browser))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99) LinearProgressIndicator(Modifier.fillMaxWidth())
            val s = server
            if (s != null && baseUrl != null) {
                Box(Modifier.fillMaxSize()) {
                    // Recreate the WebView when the trusted certificate changes.
                    key(s.certFingerprint) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                createWebView(
                                    ctx = ctx,
                                    server = s,
                                    onProgress = { progress = it },
                                    onHistory = { canGoBack = it },
                                    onCertificate = { certPrompt = it },
                                    onError = { loadError = it },
                                    log = { line ->
                                        log += line
                                        if (log.size > 200) log.removeAt(0)
                                    },
                                ).also {
                                    it.loadUrl(baseUrl)
                                    webView = it
                                }
                            },
                            onRelease = { it.destroy() },
                        )
                    }
                    if (showLog) {
                        Surface(Modifier.fillMaxSize(), tonalElevation = 4.dp) {
                            Column(Modifier.fillMaxSize().padding(12.dp)) {
                                androidx.compose.foundation.layout.Row {
                                    Text(stringResource(R.string.web_diagnostics), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        webView?.evaluateJavascript(SNAPSHOT_SCRIPT) { json -> log += "SNAPSHOT: $json" }
                                    }) { Text(stringResource(R.string.web_snapshot)) }
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) {
                                        Text(stringResource(R.string.web_copy))
                                    }
                                    TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.close)) }
                                }
                                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                                    Text(log.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    loadError?.let { message ->
                        Surface(Modifier.fillMaxSize()) {
                            Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            ) {
                                Text(stringResource(R.string.web_load_failed), style = MaterialTheme.typography.titleMedium)
                                Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                                Button(onClick = ::openExternally) { Text(stringResource(R.string.web_open_browser)) }
                            }
                        }
                    }
                }
            }
        }
    }

    certPrompt?.let { prompt ->
        CertificateDialog(
            prompt,
            onAccept = {
                certPrompt = null
                scope.launch { repository.setFingerprint(serverId, prompt.fingerprint) }
            },
            onReject = {
                certPrompt = null
                onBack()
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    ctx: android.content.Context,
    server: ServerConfig,
    onProgress: (Int) -> Unit,
    onHistory: (Boolean) -> Unit,
    onCertificate: (CertPrompt) -> Unit,
    onError: (String) -> Unit,
    log: (String) -> Unit,
): WebView = WebView(ctx).apply {
    // Allows inspecting the page from a computer with chrome://inspect.
    WebView.setWebContentsDebuggingEnabled(true)
    log("WebView ${WebView.getCurrentWebViewPackage()?.versionName ?: "?"}")
    log("Trusted fingerprint: ${server.certFingerprint ?: "none"}")
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    // Some iLO firmwares reject browsers they do not recognise: present a regular Chrome.
    settings.userAgentString = settings.userAgentString
        .replace("; wv", "")
        .replace(Regex("Version/\\S+ "), "")

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            run {
                log("JS ${message.messageLevel()}: ${message.message()} (${message.sourceId()?.substringAfterLast('/')}:${message.lineNumber()})")
            }
            return true
        }
    }
    webViewClient = object : WebViewClient() {
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val der = certificateBytes(error.certificate)
            val fp = der?.let { CertificatePinning.fingerprint(it) }
            val matches = fp != null && CertificatePinning.matches(fp, server.certFingerprint)
            log("SSL error ${error.primaryError} for ${error.url}: certificate ${fp ?: "unreadable"} -> ${if (matches) "accepted (pinned)" else "refused"}")
            if (matches) {
                handler.proceed()
            } else {
                handler.cancel()
                onCertificate(
                    CertPrompt(
                        fingerprint = fp ?: "?",
                        subject = error.certificate?.issuedTo?.dName ?: "?",
                        changed = server.certFingerprint != null,
                    ),
                )
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            log("Load error ${error.errorCode} ${error.description} for ${request.url}")
            if (request.isForMainFrame) onError("${error.description} (${error.errorCode})")
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            view.post { log("GET ${url.removePrefix("https://" + request.url.host)}") }
            return null
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            log("HTTP ${response.statusCode} for ${request.url}")
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            log("Loading $url")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            log("Renderer crashed")
            onError("Renderer crashed")
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            log("Loaded $url (title: ${view.title})")
            view.evaluateJavascript("document.body ? document.body.innerHTML.length : -1") { len -> log("Body size: $len") }
            view.evaluateJavascript(autofillScript(server.username, server.password), null)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = onHistory(view.canGoBack())
    }
}

/**
 * Fills (without submitting) the login form of the iLO web interface, which is rendered by
 * JavaScript some time after the page load: poll for a password field for a few seconds.
 */
private fun autofillScript(user: String, password: String): String = """
    (function() {
      var user = ${JSONObject.quote(user)}, pass = ${JSONObject.quote(password)};
      function set(el, v) {
        var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
        setter.call(el, v);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      }
      function tryFill(doc) {
        var pw = doc.querySelector('input[type=password]');
        if (!pw || pw.offsetParent === null || pw.value) return false;
        var inputs = Array.prototype.slice.call(doc.querySelectorAll('input[type=text],input[type=email],input:not([type])'));
        var name = inputs.filter(function(i) { return i.offsetParent !== null; })[0];
        if (name && !name.value) set(name, user);
        set(pw, pass);
        return true;
      }
      var tries = 0;
      var timer = setInterval(function() {
        var done = false;
        try { done = tryFill(document); } catch (e) {}
        for (var i = 0; !done && i < window.frames.length; i++) {
          try { done = tryFill(window.frames[i].document); } catch (e) {}
        }
        if (done || ++tries > 40) clearInterval(timer);
      }, 250);
    })();
""".trimIndent()

/** DER encoding of the certificate reported by the WebView. */
private fun certificateBytes(cert: SslCertificate?): ByteArray? {
    cert ?: return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        cert.x509Certificate?.let { return it.encoded }
    }
    val bytes = SslCertificate.saveState(cert).getByteArray("x509-certificate") ?: return null
    return runCatching {
        (CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate).encoded
    }.getOrNull()
}

/** Describes the rendered page, to understand why it may look blank. */
private val SNAPSHOT_SCRIPT = """
    (function() {
      var b = document.body, d = document.documentElement;
      function vis(el) { var st = getComputedStyle(el); return st.display + '/' + st.visibility + '/' + st.opacity; }
      return JSON.stringify({
        url: location.href, state: document.readyState, title: document.title,
        ua: navigator.userAgent,
        viewport: innerWidth + 'x' + innerHeight, scroll: d.scrollWidth + 'x' + d.scrollHeight,
        body: b ? (b.offsetWidth + 'x' + b.offsetHeight + ' ' + vis(b)) : null,
        frames: Array.prototype.map.call(document.querySelectorAll('iframe,frame'), function(f) { return f.src + ' ' + f.offsetWidth + 'x' + f.offsetHeight; }),
        scripts: Array.prototype.map.call(document.scripts, function(s) { return s.src || ('inline:' + s.text.length); }),
        cookies: document.cookie.replace(/=[^;]*/g, '=...'),
        text: (b ? b.innerText : '').substring(0, 400),
        html: d.outerHTML.substring(0, 6000)
      });
    })();
""".trimIndent()
