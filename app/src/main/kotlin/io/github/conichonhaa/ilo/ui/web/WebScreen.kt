package io.github.conichonhaa.ilo.ui.web

import android.annotation.SuppressLint
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.conichonhaa.ilo.R
import io.github.conichonhaa.ilo.core.net.CertificatePinning
import io.github.conichonhaa.ilo.core.net.IloClient
import io.github.conichonhaa.ilo.core.net.IloSession
import io.github.conichonhaa.ilo.data.ServerConfig
import io.github.conichonhaa.ilo.data.ServerRepository
import io.github.conichonhaa.ilo.ui.common.CertPrompt
import io.github.conichonhaa.ilo.ui.common.CertificateDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class WebUiState(
    val server: ServerConfig? = null,
    /** Set once the page can be loaded (after an optional automatic login). */
    val ready: Boolean = false,
    val sessionKey: String? = null,
    val certPrompt: CertPrompt? = null,
)

class WebViewModel(private val repository: ServerRepository, private val serverId: String) : ViewModel() {
    private val _state = MutableStateFlow(WebUiState())
    val state: StateFlow<WebUiState> = _state.asStateFlow()
    private var session: IloSession? = null
    private var client: IloClient? = null

    init {
        prepare()
    }

    /** Logs in through the JSON API so the web interface opens already authenticated. */
    fun prepare() {
        viewModelScope.launch {
            val s = repository.get(serverId) ?: return@launch
            _state.update { it.copy(server = s, ready = false, certPrompt = null) }
            val c = IloClient(s.address, s.certFingerprint)
            client = c
            val result = withContext(Dispatchers.IO) { runCatching { c.login(s.username, s.password) } }
            session = result.getOrNull()
            val prompt = CertPrompt.from(result.exceptionOrNull())
            _state.update {
                it.copy(ready = prompt == null, sessionKey = session?.sessionKey, certPrompt = prompt)
            }
        }
    }

    fun acceptCertificate(prompt: CertPrompt) {
        viewModelScope.launch {
            repository.setFingerprint(serverId, prompt.fingerprint)
            prepare()
        }
    }

    override fun onCleared() {
        val c = client
        val s = session ?: return
        Thread { c?.logout(s) }.start()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(repository: ServerRepository, serverId: String, onBack: () -> Unit) {
    val vm: WebViewModel = viewModel(
        key = "web-$serverId",
        factory = viewModelFactory { initializer { WebViewModel(repository, serverId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var sslMismatch by remember { mutableStateOf<CertPrompt?>(null) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.server?.displayName ?: stringResource(R.string.web_interface)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99 || !state.ready) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val server = state.server
            if (state.ready && server != null) {
                val baseUrl = IloClient(server.address, server.certFingerprint).baseUrl
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    val der = certificateBytes(error.certificate)
                                    val fp = der?.let { CertificatePinning.fingerprint(it) }
                                    if (fp != null && CertificatePinning.matches(fp, server.certFingerprint)) {
                                        handler.proceed()
                                    } else {
                                        handler.cancel()
                                        sslMismatch = CertPrompt(
                                            fingerprint = fp ?: "?",
                                            subject = error.certificate?.issuedTo?.dName ?: "?",
                                            changed = server.certFingerprint != null,
                                        )
                                    }
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                }
                            }
                            val cookies = CookieManager.getInstance()
                            cookies.setAcceptCookie(true)
                            state.sessionKey?.let { cookies.setCookie(baseUrl, "sessionKey=$it; path=/; secure") }
                            loadUrl("$baseUrl/")
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }

    state.certPrompt?.let { prompt ->
        CertificateDialog(prompt, onAccept = { vm.acceptCertificate(prompt) }, onReject = onBack)
    }
    sslMismatch?.let { prompt ->
        CertificateDialog(
            prompt,
            onAccept = {
                sslMismatch = null
                vm.acceptCertificate(prompt)
            },
            onReject = {
                sslMismatch = null
                onBack()
            },
        )
    }
}

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
