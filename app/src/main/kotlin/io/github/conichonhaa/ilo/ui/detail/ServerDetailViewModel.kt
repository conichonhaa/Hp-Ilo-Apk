package io.github.conichonhaa.ilo.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.conichonhaa.ilo.core.net.IloClient
import io.github.conichonhaa.ilo.core.net.IloException
import io.github.conichonhaa.ilo.core.net.ResetType
import io.github.conichonhaa.ilo.core.net.ServerOverview
import io.github.conichonhaa.ilo.data.ServerConfig
import io.github.conichonhaa.ilo.data.ServerRepository
import io.github.conichonhaa.ilo.ui.common.CertPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailUiState(
    val server: ServerConfig? = null,
    val loading: Boolean = false,
    val overview: ServerOverview? = null,
    val error: Throwable? = null,
    val certPrompt: CertPrompt? = null,
    val actionRunning: Boolean = false,
    val actionError: Throwable? = null,
    val actionDone: ResetType? = null,
)

class ServerDetailViewModel(private val repository: ServerRepository, private val serverId: String) : ViewModel() {
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.servers.collect { list ->
                val s = list.firstOrNull { it.id == serverId }
                val previous = _state.value.server
                _state.update { it.copy(server = s) }
                if (s != null && (previous == null || previous.address != s.address ||
                        previous.certFingerprint != s.certFingerprint || previous.password != s.password)
                ) {
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        val s = _state.value.server ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) { runCatching { fetchOverview(s) } }
            _state.update {
                it.copy(
                    loading = false,
                    overview = result.getOrNull() ?: it.overview,
                    error = result.exceptionOrNull(),
                    certPrompt = CertPrompt.from(result.exceptionOrNull()),
                )
            }
        }
    }

    private fun fetchOverview(s: ServerConfig): ServerOverview {
        val client = IloClient(s.address, s.certFingerprint)
        return try {
            client.redfishOverview(s.username, s.password)
        } catch (e: IloException) {
            if (e.kind == IloException.Kind.TLS || e.kind == IloException.Kind.AUTH_FAILED ||
                e.kind == IloException.Kind.UNREACHABLE || e.kind == IloException.Kind.UNKNOWN_HOST ||
                e.kind == IloException.Kind.TIMEOUT
            ) {
                throw e
            }
            // iLO 3 and old iLO 4 firmware: no Redfish, use the JSON API of the web interface.
            val session = client.login(s.username, s.password)
            try {
                client.overview(session) ?: ServerOverview()
            } finally {
                client.logout(session)
            }
        }
    }

    fun acceptCertificate() {
        val prompt = _state.value.certPrompt ?: return
        _state.update { it.copy(certPrompt = null) }
        viewModelScope.launch { repository.setFingerprint(serverId, prompt.fingerprint) }
    }

    fun rejectCertificate() = _state.update { it.copy(certPrompt = null) }

    fun power(type: ResetType) {
        val s = _state.value.server ?: return
        viewModelScope.launch {
            _state.update { it.copy(actionRunning = true, actionError = null, actionDone = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { IloClient(s.address, s.certFingerprint).redfishReset(s.username, s.password, type) }
            }
            _state.update {
                it.copy(actionRunning = false, actionError = result.exceptionOrNull(), actionDone = type.takeIf { result.isSuccess })
            }
            if (result.isSuccess) {
                delay(3000)
                refresh()
            }
        }
    }

    fun clearAction() = _state.update { it.copy(actionError = null, actionDone = null) }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(serverId)
            onDeleted()
        }
    }
}
