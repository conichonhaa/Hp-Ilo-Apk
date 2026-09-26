package io.github.conichonhaa.ilo.ui.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.conichonhaa.ilo.core.crypto.CipherType
import io.github.conichonhaa.ilo.core.input.KeyboardLayout
import io.github.conichonhaa.ilo.core.net.IloClient
import io.github.conichonhaa.ilo.core.rc.CloseReason
import io.github.conichonhaa.ilo.core.rc.KvmSession
import io.github.conichonhaa.ilo.core.rc.PowerAction
import io.github.conichonhaa.ilo.data.ServerRepository
import io.github.conichonhaa.ilo.ui.common.CertPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConsolePhase { CONNECTING, CONNECTED, CLOSED }

data class ConsoleUiState(
    val serverName: String = "",
    val phase: ConsolePhase = ConsolePhase.CONNECTING,
    val step: KvmSession.Step? = null,
    val width: Int = 0,
    val height: Int = 0,
    val hasSignal: Boolean = true,
    val encryption: Int? = null,
    val powerOn: Boolean? = null,
    val health: Int? = null,
    val postCode: Int? = null,
    val unlicensed: Boolean = false,
    val closeReason: CloseReason? = null,
    val closeError: Throwable? = null,
    val certPrompt: CertPrompt? = null,
    val notice: KvmSession.Notice? = null,
    val layout: KeyboardLayout = KeyboardLayout.US,
    val sticky: Set<Int> = emptySet(),
) {
    val encrypted: Boolean? get() = encryption?.let { it != CipherType.NONE }
}

class ConsoleViewModel(private val repository: ServerRepository, private val serverId: String) : ViewModel() {
    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()

    private val _session = MutableStateFlow<KvmSession?>(null)
    val session: StateFlow<KvmSession?> = _session.asStateFlow()

    val keyboard = RemoteKeyboard { _session.value }

    /** Invoked (from a background thread) whenever the remote screen changed. */
    @Volatile
    var frameListener: (() -> Unit)? = null

    init {
        keyboard.onStickyChanged = { sticky -> _state.update { it.copy(sticky = sticky) } }
        connect()
    }

    fun connect() {
        _session.value?.stop()
        _session.value = null
        viewModelScope.launch {
            val server = repository.get(serverId)
            if (server == null) {
                _state.update { it.copy(phase = ConsolePhase.CLOSED, closeReason = CloseReason.USER) }
                return@launch
            }
            _state.value = ConsoleUiState(serverName = server.displayName, layout = server.layout)
            val client = IloClient(server.address, server.certFingerprint)
            lateinit var session: KvmSession
            val listener = object : KvmSession.Listener {
                private fun current() = _session.value === session

                private fun update(f: (ConsoleUiState) -> ConsoleUiState) {
                    if (current()) _state.update { f(it) }
                }

                override fun onConnecting(step: KvmSession.Step) = update { it.copy(step = step) }

                override fun onConnected() {
                    update { it.copy(phase = ConsolePhase.CONNECTED) }
                    viewModelScope.launch { repository.markConnected(serverId) }
                }

                override fun onVideoMode(width: Int, height: Int, hasSignal: Boolean) =
                    update { it.copy(width = width, height = height, hasSignal = hasSignal) }

                override fun onFrameUpdated() {
                    if (current()) frameListener?.invoke()
                }

                override fun onEncryption(type: Int) = update { it.copy(encryption = type) }
                override fun onLicense(licensed: Boolean) = update { it.copy(unlicensed = !licensed) }
                override fun onPowerState(on: Boolean) = update { it.copy(powerOn = on) }
                override fun onHealth(status: Int) = update { it.copy(health = status) }
                override fun onPostCode(code: Int) = update { it.copy(postCode = code) }
                override fun onNotice(notice: KvmSession.Notice) = update { it.copy(notice = notice) }

                override fun onClosed(reason: CloseReason, error: Throwable?) = update {
                    it.copy(
                        phase = ConsolePhase.CLOSED,
                        closeReason = reason,
                        closeError = error,
                        certPrompt = CertPrompt.from(error),
                    )
                }
            }
            session = KvmSession(client, server.username, server.password, listener)
            session.layout = server.layout
            _session.value = session
            session.start()
        }
    }

    fun acceptCertificate() {
        val prompt = _state.value.certPrompt ?: return
        viewModelScope.launch {
            repository.setFingerprint(serverId, prompt.fingerprint)
            connect()
        }
    }

    fun setLayout(layout: KeyboardLayout) {
        _session.value?.layout = layout
        _state.update { it.copy(layout = layout) }
        viewModelScope.launch {
            repository.get(serverId)?.let { repository.upsert(it.copy(layout = layout)) }
        }
    }

    fun power(action: PowerAction) {
        _session.value?.power(action)
    }

    fun ctrlAltDel() {
        _session.value?.ctrlAltDel()
    }

    fun requestRefresh() {
        _session.value?.requestRefresh()
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun disconnect() {
        _session.value?.stop()
    }

    override fun onCleared() {
        _session.value?.stop()
        frameListener = null
    }
}
