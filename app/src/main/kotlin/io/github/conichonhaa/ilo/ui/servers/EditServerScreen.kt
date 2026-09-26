package io.github.conichonhaa.ilo.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.conichonhaa.ilo.R
import io.github.conichonhaa.ilo.core.input.KeyboardLayout
import io.github.conichonhaa.ilo.data.ServerConfig
import io.github.conichonhaa.ilo.data.ServerRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditServerScreen(repository: ServerRepository, serverId: String?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<ServerConfig?>(null) }
    var loaded by remember { mutableStateOf(serverId == null) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var layout by remember { mutableStateOf(KeyboardLayout.US) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(serverId) {
        if (serverId != null) {
            repository.get(serverId)?.let {
                original = it
                name = it.name
                address = it.address
                username = it.username
                password = it.password
                layout = it.layout
                fingerprint = it.certFingerprint
            }
            loaded = true
        }
    }

    val valid = address.isNotBlank() && username.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (serverId == null) R.string.add_server else R.string.edit_server)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = valid && loaded,
                        onClick = {
                            val base = original ?: ServerConfig()
                            val trimmed = address.trim()
                            val addressChanged = original != null && original?.address != trimmed
                            scope.launch {
                                repository.upsert(
                                    base.copy(
                                        name = name.trim(),
                                        address = trimmed,
                                        username = username.trim(),
                                        password = password,
                                        layout = layout,
                                        // A new address means a different certificate.
                                        certFingerprint = if (addressChanged) null else fingerprint,
                                    ),
                                )
                                onDone()
                            }
                        },
                    ) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.field_address)) },
                supportingText = { Text(stringResource(R.string.field_address_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.field_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.field_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.show_password),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.field_layout), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.field_layout_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyboardLayout.entries.forEach { l ->
                    FilterChip(
                        selected = layout == l,
                        onClick = { layout = l },
                        label = { Text(stringResource(if (l == KeyboardLayout.FR) R.string.layout_fr else R.string.layout_us)) },
                    )
                }
            }

            Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.titleSmall)
            Text(
                fingerprint ?: stringResource(R.string.cert_none),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (fingerprint != null) {
                OutlinedButton(onClick = { fingerprint = null }) { Text(stringResource(R.string.cert_forget)) }
            }
        }
    }
}
