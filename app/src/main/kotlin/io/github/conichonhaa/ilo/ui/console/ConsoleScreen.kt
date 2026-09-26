package io.github.conichonhaa.ilo.ui.console

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.conichonhaa.ilo.R
import io.github.conichonhaa.ilo.core.input.Hid
import io.github.conichonhaa.ilo.core.input.KeyboardLayout
import io.github.conichonhaa.ilo.core.rc.CloseReason
import io.github.conichonhaa.ilo.core.rc.KvmSession
import io.github.conichonhaa.ilo.core.rc.PowerAction
import io.github.conichonhaa.ilo.data.ServerRepository
import io.github.conichonhaa.ilo.ui.common.CertificateDialog
import io.github.conichonhaa.ilo.ui.common.ErrorDialog
import io.github.conichonhaa.ilo.ui.common.describeClose

@Composable
fun ConsoleScreen(repository: ServerRepository, serverId: String, onExit: () -> Unit) {
    // Leaving can be triggered both by the user and by the session closing: pop only once.
    var exited by remember { mutableStateOf(false) }
    val exit = {
        if (!exited) {
            exited = true
            onExit()
        }
    }
    val vm: ConsoleViewModel = viewModel(
        key = "console-$serverId",
        factory = viewModelFactory { initializer { ConsoleViewModel(repository, serverId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showKeys by rememberSaveable { mutableStateOf(true) }
    var keyboardVisible by remember { mutableStateOf(false) }
    var kvmView by remember { mutableStateOf<KvmView?>(null) }

    ImmersiveMode()

    BackHandler {
        vm.disconnect()
        exit()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        ConsoleTopBar(
            state = state,
            onBack = {
                vm.disconnect()
                exit()
            },
            onToggleKeyboard = {
                val v = kvmView ?: return@ConsoleTopBar
                if (keyboardVisible) v.hideSoftKeyboard() else v.showSoftKeyboard()
                keyboardVisible = !keyboardVisible
            },
            onToggleKeys = { showKeys = !showKeys },
            onPower = vm::power,
            onCtrlAltDel = vm::ctrlAltDel,
            onRefresh = vm::requestRefresh,
            onResetZoom = { kvmView?.resetZoom() },
            onLayout = vm::setLayout,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    KvmView(ctx, vm.keyboard).also { view ->
                        view.keepScreenOn = true
                        vm.frameListener = { view.postInvalidateOnAnimation() }
                        kvmView = view
                    }
                },
                update = { view -> view.session = session },
                onRelease = { vm.frameListener = null },
            )
            if (state.phase == ConsolePhase.CONNECTING) {
                ConnectingOverlay(state.step, Modifier.align(Alignment.Center))
            } else if (state.phase == ConsolePhase.CONNECTED && !state.hasSignal) {
                Text(
                    stringResource(R.string.console_no_signal),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        if (showKeys && state.phase == ConsolePhase.CONNECTED) {
            SpecialKeysBar(
                sticky = state.sticky,
                onTap = { usage -> vm.keyboard.tap(usage) },
                onSticky = { usage -> vm.keyboard.toggleSticky(usage) },
                onCtrlAltDel = vm::ctrlAltDel,
            )
        }
    }

    // Dialogs --------------------------------------------------------------
    val prompt = state.certPrompt
    if (state.phase == ConsolePhase.CLOSED) {
        when {
            prompt != null -> CertificateDialog(prompt, onAccept = vm::acceptCertificate, onReject = exit)
            state.closeReason == CloseReason.USER || state.closeReason == null -> LaunchedEffect(Unit) { exit() }
            else -> ErrorDialog(
                message = describeClose(context, state.closeReason!!, state.closeError) ?: "",
                onDismiss = exit,
                onRetry = vm::connect,
            )
        }
    }

    state.notice?.let { notice ->
        val text = when (notice) {
            is KvmSession.Notice.ShareRequestDenied -> stringResource(R.string.notice_share_denied, notice.user, notice.address)
            is KvmSession.Notice.SeizedBy -> stringResource(R.string.notice_seized, notice.user, notice.address)
            is KvmSession.Notice.Unauthorized -> stringResource(R.string.notice_unauthorized)
            KvmSession.Notice.FirmwareUpdate -> stringResource(R.string.notice_firmware)
        }
        if (notice !is KvmSession.Notice.SeizedBy) {
            AlertDialog(
                onDismissRequest = vm::dismissNotice,
                text = { Text(text) },
                confirmButton = { TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.ok)) } },
            )
        }
    }
}

@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun ConnectingOverlay(step: KvmSession.Step?, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(
                    when (step) {
                        null, KvmSession.Step.LOGIN -> R.string.step_login
                        KvmSession.Step.RC_INFO -> R.string.step_rc_info
                        KvmSession.Step.VIDEO_CHANNEL -> R.string.step_video
                        KvmSession.Step.COMMAND_CHANNEL -> R.string.step_command
                    },
                ),
            )
        }
    }
}

@Composable
private fun ConsoleTopBar(
    state: ConsoleUiState,
    onBack: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleKeys: () -> Unit,
    onPower: (PowerAction) -> Unit,
    onCtrlAltDel: () -> Unit,
    onRefresh: () -> Unit,
    onResetZoom: () -> Unit,
    onLayout: (KeyboardLayout) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var powerMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PowerAction?>(null) }
    val onBar = Color.White

    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.disconnect), tint = onBar)
        }
        Column(Modifier.weight(1f)) {
            Text(state.serverName, color = onBar, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            val status = buildList {
                if (state.width > 0 && state.hasSignal) add("${state.width}×${state.height}")
                state.postCode?.let { add("POST %04X".format(it)) }
                if (state.unlicensed) add(stringResource(R.string.console_unlicensed))
            }.joinToString(" · ")
            if (status.isNotEmpty()) {
                Text(status, color = Color(0xFFB0B0B0), maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
        state.health?.let { health ->
            val c = when (health) {
                0 -> Color(0xFF43A047)
                1 -> Color(0xFFFDD835)
                else -> Color(0xFFE53935)
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(8.dp))
        }
        state.encrypted?.let { enc ->
            Icon(
                if (enc) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = stringResource(if (enc) R.string.console_encrypted else R.string.console_not_encrypted),
                tint = if (enc) onBar else Color(0xFFFDD835),
                modifier = Modifier.size(18.dp),
            )
        }
        Box {
            IconButton(onClick = { powerMenu = true }, enabled = state.phase == ConsolePhase.CONNECTED) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    stringResource(R.string.power_control),
                    tint = when (state.powerOn) {
                        true -> Color(0xFF43A047)
                        false -> Color(0xFFE53935)
                        null -> onBar
                    },
                )
            }
            DropdownMenu(expanded = powerMenu, onDismissRequest = { powerMenu = false }) {
                PowerAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(stringResource(powerLabel(action))) },
                        onClick = {
                            powerMenu = false
                            confirm = action
                        },
                    )
                }
            }
        }
        IconButton(onClick = onToggleKeyboard, enabled = state.phase == ConsolePhase.CONNECTED) {
            Icon(Icons.Default.Keyboard, stringResource(R.string.console_keyboard), tint = onBar)
        }
        IconButton(onClick = onToggleKeys) {
            Icon(Icons.Default.Tune, stringResource(R.string.console_special_keys), tint = onBar)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more), tint = onBar) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Ctrl+Alt+Del") }, onClick = { menu = false; onCtrlAltDel() })
                DropdownMenuItem(text = { Text(stringResource(R.string.console_refresh)) }, onClick = { menu = false; onRefresh() })
                DropdownMenuItem(text = { Text(stringResource(R.string.console_fit)) }, onClick = { menu = false; onResetZoom() })
                HorizontalDivider()
                KeyboardLayout.entries.forEach { l ->
                    DropdownMenuItem(
                        text = {
                            val name = stringResource(if (l == KeyboardLayout.FR) R.string.layout_fr else R.string.layout_us)
                            Text(if (l == state.layout) "✓ $name" else name)
                        },
                        onClick = { menu = false; onLayout(l) },
                    )
                }
            }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(powerLabel(action))) },
            text = { Text(stringResource(R.string.power_confirm, state.serverName)) },
            confirmButton = { TextButton(onClick = { confirm = null; onPower(action) }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun powerLabel(action: PowerAction) = when (action) {
    PowerAction.PRESS -> R.string.power_press
    PowerAction.PRESS_AND_HOLD -> R.string.power_hold
    PowerAction.COLD_BOOT -> R.string.power_cold_boot
    PowerAction.RESET -> R.string.power_reset
}

@Composable
private fun SpecialKeysBar(
    sticky: Set<Int>,
    onTap: (Int) -> Unit,
    onSticky: (Int) -> Unit,
    onCtrlAltDel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StickyKey("Ctrl", Hid.LEFT_CTRL, sticky, onSticky)
        StickyKey("Alt", Hid.LEFT_ALT, sticky, onSticky)
        StickyKey("AltGr", Hid.RIGHT_ALT, sticky, onSticky)
        StickyKey("Win", Hid.LEFT_GUI, sticky, onSticky)
        StickyKey("Shift", Hid.LEFT_SHIFT, sticky, onSticky)
        Key("Esc") { onTap(Hid.ESCAPE) }
        Key("Tab") { onTap(Hid.TAB) }
        Key("↵") { onTap(Hid.ENTER) }
        Key("⌫") { onTap(Hid.BACKSPACE) }
        Key("Del") { onTap(Hid.DELETE) }
        Key("←") { onTap(Hid.LEFT) }
        Key("↑") { onTap(Hid.UP) }
        Key("↓") { onTap(Hid.DOWN) }
        Key("→") { onTap(Hid.RIGHT) }
        Key("Home") { onTap(Hid.HOME) }
        Key("End") { onTap(Hid.END) }
        Key("PgUp") { onTap(Hid.PAGE_UP) }
        Key("PgDn") { onTap(Hid.PAGE_DOWN) }
        Key("Ins") { onTap(Hid.INSERT) }
        for (i in 1..12) Key("F$i") { onTap(Hid.function(i)) }
        Key("PrtSc") { onTap(Hid.PRINT_SCREEN) }
        Key("Ctrl+Alt+Del", onClick = onCtrlAltDel)
    }
}

@Composable
private fun StickyKey(label: String, usage: Int, sticky: Set<Int>, onSticky: (Int) -> Unit) {
    FilterChip(selected = usage in sticky, onClick = { onSticky(usage) }, label = { Text(label) })
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = Color.White) }
}
