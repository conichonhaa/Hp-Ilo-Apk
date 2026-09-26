package io.github.conichonhaa.ilo.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.conichonhaa.ilo.R
import io.github.conichonhaa.ilo.core.net.ResetType
import io.github.conichonhaa.ilo.data.ServerRepository
import io.github.conichonhaa.ilo.ui.common.CertificateDialog
import io.github.conichonhaa.ilo.ui.common.describeError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    repository: ServerRepository,
    serverId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onConsole: () -> Unit,
    onWeb: () -> Unit,
) {
    val vm: ServerDetailViewModel = viewModel(
        key = "detail-$serverId",
        factory = viewModelFactory { initializer { ServerDetailViewModel(repository, serverId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmPower by remember { mutableStateOf<ResetType?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.actionDone, state.actionError) {
        val msg = when {
            state.actionDone != null -> context.getString(R.string.power_sent)
            state.actionError != null -> describeError(context, state.actionError)
            else -> null
        }
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.clearAction()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.server?.displayName ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.edit_server)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConsole, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DesktopWindows, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_console))
                }
                FilledTonalButton(onClick = onWeb, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Public, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.web_interface))
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.overview), style = MaterialTheme.typography.titleMedium)
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val o = state.overview
                    if (state.error != null && state.certPrompt == null) {
                        Text(describeError(context, state.error), color = MaterialTheme.colorScheme.error)
                    }
                    if (o != null) {
                        InfoRow(R.string.info_address, state.server?.address)
                        InfoRow(R.string.info_product, o.productName)
                        InfoRow(R.string.info_hostname, o.serverName)
                        InfoRow(R.string.info_serial, o.serialNumber)
                        InfoRow(R.string.info_power, o.powerState)
                        InfoRow(R.string.info_health, o.health, healthColor(o.health))
                        InfoRow(R.string.info_ilo, listOfNotNull(o.iloModel, o.iloFirmware).joinToString(" ").ifEmpty { null })
                        InfoRow(R.string.info_bios, o.biosVersion)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.power_control), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        if (state.actionRunning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text(stringResource(R.string.power_control_hint), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    ResetType.entries.forEach { type ->
                        OutlinedButton(
                            onClick = { confirmPower = type },
                            enabled = !state.actionRunning && state.server != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(labelFor(type))) }
                    }
                }
            }
        }
    }

    confirmPower?.let { type ->
        AlertDialog(
            onDismissRequest = { confirmPower = null },
            title = { Text(stringResource(labelFor(type))) },
            text = { Text(stringResource(R.string.power_confirm, state.server?.displayName ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPower = null
                    vm.power(type)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmPower = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm, state.server?.displayName ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    state.certPrompt?.let { CertificateDialog(it, onAccept = vm::acceptCertificate, onReject = vm::rejectCertificate) }
}

private fun labelFor(type: ResetType) = when (type) {
    ResetType.ON -> R.string.power_on
    ResetType.PUSH_POWER_BUTTON -> R.string.power_push
    ResetType.FORCE_OFF -> R.string.power_force_off
    ResetType.FORCE_RESTART -> R.string.power_force_restart
}

@Composable
private fun healthColor(health: String?): Color? = when (health?.uppercase()) {
    "OK" -> Color(0xFF2E7D32)
    "WARNING", "DEGRADED" -> Color(0xFFF9A825)
    "CRITICAL" -> MaterialTheme.colorScheme.error
    else -> null
}

@Composable
private fun InfoRow(label: Int, value: String?, color: Color? = null) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
        )
    }
}
