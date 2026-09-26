package io.github.conichonhaa.ilo.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.conichonhaa.ilo.R
import io.github.conichonhaa.ilo.core.net.CertificatePinning
import io.github.conichonhaa.ilo.core.net.IloException
import io.github.conichonhaa.ilo.core.net.UntrustedCertificateException
import io.github.conichonhaa.ilo.core.rc.CloseReason

/** A certificate waiting for the user's decision. */
data class CertPrompt(val fingerprint: String, val subject: String, val changed: Boolean) {
    companion object {
        fun from(error: Throwable?): CertPrompt? =
            CertificatePinning.findUntrusted(error)?.let { from(it) }

        fun from(e: UntrustedCertificateException) = CertPrompt(e.fingerprint, e.subject, e.changed)
    }
}

@Composable
fun CertificateDialog(prompt: CertPrompt, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(if (prompt.changed) Icons.Default.Warning else Icons.Default.Security, contentDescription = null) },
        title = {
            Text(stringResource(if (prompt.changed) R.string.cert_changed_title else R.string.cert_new_title))
        },
        text = {
            Column {
                Text(stringResource(if (prompt.changed) R.string.cert_changed_message else R.string.cert_new_message))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.cert_subject), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(prompt.subject, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(prompt.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(if (prompt.changed) R.string.cert_replace else R.string.cert_trust))
            }
        },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.cancel)) } },
    )
}

fun describeError(context: Context, error: Throwable?): String {
    val e = error as? IloException ?: return error?.message ?: context.getString(R.string.error_unknown)
    val res = when (e.kind) {
        IloException.Kind.AUTH_FAILED -> R.string.error_auth
        IloException.Kind.UNKNOWN_HOST -> R.string.error_unknown_host
        IloException.Kind.UNREACHABLE -> R.string.error_unreachable
        IloException.Kind.TIMEOUT -> R.string.error_timeout
        IloException.Kind.TLS -> R.string.error_tls
        IloException.Kind.NOT_SUPPORTED -> R.string.error_not_supported
        IloException.Kind.PROTOCOL, IloException.Kind.HTTP -> null
    }
    return if (res != null) context.getString(res) else context.getString(R.string.error_with_detail, e.message)
}

fun describeClose(context: Context, reason: CloseReason, error: Throwable?): String? = when (reason) {
    CloseReason.USER -> null
    CloseReason.LOGIN_FAILED -> describeError(context, error)
    CloseReason.RC_UNAVAILABLE -> context.getString(R.string.close_rc_unavailable) +
        (error?.message?.let { "\n\n$it" } ?: "")
    CloseReason.CONNECT_FAILED -> context.getString(R.string.close_connect_failed)
    CloseReason.DENIED -> context.getString(R.string.close_denied)
    CloseReason.BUSY -> context.getString(R.string.close_busy)
    CloseReason.NO_FREE_SESSION -> context.getString(R.string.close_no_free_session)
    CloseReason.NOT_LICENSED -> context.getString(R.string.close_not_licensed)
    CloseReason.HANDSHAKE_FAILED -> context.getString(R.string.close_handshake)
    CloseReason.CONNECTION_LOST -> context.getString(R.string.close_connection_lost)
    CloseReason.SEIZED -> context.getString(R.string.close_seized)
    CloseReason.SERVER_CLOSED -> context.getString(R.string.close_server_closed)
}

@Composable
fun ErrorDialog(message: String, onDismiss: () -> Unit, onRetry: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_title)) },
        text = { Text(message) },
        confirmButton = {
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = if (onRetry != null) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
        } else {
            null
        },
    )
}
