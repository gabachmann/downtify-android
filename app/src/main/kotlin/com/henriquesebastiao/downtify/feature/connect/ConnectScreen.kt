package com.henriquesebastiao.downtify.feature.connect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.henriquesebastiao.downtify.R
import com.henriquesebastiao.downtify.core.designsystem.component.DowntifyIcons
import com.henriquesebastiao.downtify.core.designsystem.component.DowntifyLogo
import com.henriquesebastiao.downtify.core.designsystem.theme.DowntifyTheme
import com.henriquesebastiao.downtify.core.designsystem.theme.Spacing
import com.henriquesebastiao.downtify.core.model.Capabilities
import com.henriquesebastiao.downtify.core.model.PairingPayload
import com.henriquesebastiao.downtify.core.model.ServerInfo
import com.henriquesebastiao.downtify.core.network.discovery.DiscoveredServer

@Composable
fun ConnectRoute(
    pairingRequest: PairingPayload?,
    onPairingRequestHandled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val flowState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = flowState.copy(address = viewModel.address, code = viewModel.code)
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var localNetworkDenied by remember { mutableStateOf(false) }
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localNetworkDenied = !granted
        viewModel.startDiscovery()
    }
    LaunchedEffect(Unit) {
        // Android 17+ keeps apps off the home network without this; a typed
        // 192.168.x.x address, the stream and the WebSocket all need it.
        val needed = Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK &&
            ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (needed) localNetwork.launch(ACCESS_LOCAL_NETWORK) else viewModel.startDiscovery()
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) viewModel.startScan()
    }

    LaunchedEffect(pairingRequest) {
        if (pairingRequest != null) {
            viewModel.onPairingPayload(pairingRequest)
            onPairingRequestHandled()
        }
    }

    if (state.scanning) {
        BackHandler { viewModel.stopScan() }
        QrScanner(onResult = viewModel::onScannedText, onClose = viewModel::stopScan, modifier = modifier)
        return
    }

    ConnectScreen(
        state = state,
        permissionDenied = permissionDenied,
        localNetworkDenied = localNetworkDenied,
        onAddressChange = viewModel::onAddressChange,
        onPickDiscovered = viewModel::onPickDiscovered,
        onConnect = viewModel::connect,
        onCodeChange = viewModel::onCodeChange,
        onPair = viewModel::pair,
        onBackToAddress = viewModel::backToAddress,
        onScan = {
            permissionDenied = false
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            if (granted == PackageManager.PERMISSION_GRANTED) {
                viewModel.startScan()
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun ConnectScreen(
    state: ConnectUiState,
    onAddressChange: (String) -> Unit,
    onPickDiscovered: (DiscoveredServer) -> Unit,
    onConnect: () -> Unit,
    onCodeChange: (String) -> Unit,
    onPair: () -> Unit,
    onBackToAddress: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    permissionDenied: Boolean = false,
    localNetworkDenied: Boolean = false,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                AnimatedContent(targetState = state.step, label = "connectStep") { step ->
                    when (step) {
                        ConnectStep.Address ->
                            AddressStep(
                                state,
                                onAddressChange,
                                onPickDiscovered,
                                onConnect,
                                onScan,
                                permissionDenied,
                                localNetworkDenied,
                            )

                        is ConnectStep.Pair ->
                            PairStep(state, step, onCodeChange, onPair, onBackToAddress, onScan, permissionDenied)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressStep(
    state: ConnectUiState,
    onAddressChange: (String) -> Unit,
    onPickDiscovered: (DiscoveredServer) -> Unit,
    onConnect: () -> Unit,
    onScan: () -> Unit,
    permissionDenied: Boolean,
    localNetworkDenied: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            DowntifyLogo(size = 64.dp)
            val accent = MaterialTheme.colorScheme.primary
            Text(
                buildAnnotatedString {
                    append(stringResource(R.string.connect_title_line1))
                    append('\n')
                    withStyle(SpanStyle(color = accent)) { append(stringResource(R.string.connect_title_line2)) }
                },
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.connect_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.revoked) Notice(stringResource(R.string.connect_revoked), DowntifyIcons.Unpair)
        if (localNetworkDenied) Notice(stringResource(R.string.connect_local_network_denied), DowntifyIcons.Warning)

        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            label = { Text(stringResource(R.string.connect_address_label)) },
            placeholder = { Text("http://192.168.1.20:8000") },
            leadingIcon = { Icon(painterResource(DowntifyIcons.Server), contentDescription = null) },
            supportingText = {
                Text(errorText(state.error) ?: stringResource(R.string.connect_address_hint))
            },
            isError = state.error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { if (state.canConnect) onConnect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.cleartextWarning) Notice(stringResource(R.string.connect_cleartext_warning), DowntifyIcons.Warning)

        DiscoveredList(state, onPickDiscovered)

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                onClick = onConnect,
                enabled = state.canConnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.connect_action), style = MaterialTheme.typography.titleMedium)
                }
            }
            ScanButton(stringResource(R.string.connect_scan), onScan, state.busy)
            if (permissionDenied) Notice(stringResource(R.string.scan_permission_denied), DowntifyIcons.Warning)
            if (state.error == ConnectError.NotAPairingCode || state.error == ConnectError.OtherServer) {
                Notice(errorText(state.error).orEmpty(), DowntifyIcons.Error)
            }
        }
    }
}

@Composable
private fun DiscoveredList(state: ConnectUiState, onPick: (DiscoveredServer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.selectableGroup()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(
                painterResource(DowntifyIcons.Wifi),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.connect_found),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (state.discovered.isEmpty()) {
            Text(
                stringResource(R.string.connect_none_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        }
        state.discovered.forEach { server ->
            val selected = state.address.trimEnd('/') == server.baseUrl
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onPick(server) },
                    ),
            ) {
                Row(
                    Modifier.heightIn(min = 72.dp).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    DowntifyLogo(size = 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.connect_server_subtitle, server.address, server.version),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    RadioButton(selected = selected, onClick = null)
                }
            }
        }
    }
}

@Composable
private fun PairStep(
    state: ConnectUiState,
    step: ConnectStep.Pair,
    onCodeChange: (String) -> Unit,
    onPair: () -> Unit,
    onBack: () -> Unit,
    onScan: () -> Unit,
    permissionDenied: Boolean,
) {
    BackHandler(onBack = onBack)
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        DowntifyLogo(size = 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                stringResource(R.string.pair_title, step.info.name.ifBlank { step.baseUrl }),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.connect_server_subtitle, step.baseUrl.substringAfter("://"), step.info.version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.pair_body), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = { Text(stringResource(R.string.pair_code_label)) },
            placeholder = { Text(stringResource(R.string.pair_code_placeholder)) },
            leadingIcon = { Icon(painterResource(DowntifyIcons.Keyboard), contentDescription = null) },
            supportingText = errorText(state.error)?.let { { Text(it) } },
            isError = state.error != null,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            visualTransformation = UppercaseTransformation,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { if (state.canPair) onPair() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                onClick = onPair,
                enabled = state.canPair,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.pair_action), style = MaterialTheme.typography.titleMedium)
                }
            }
            ScanButton(stringResource(R.string.pair_scan), onScan, state.busy)
            if (permissionDenied) Notice(stringResource(R.string.scan_permission_denied), DowntifyIcons.Warning)
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_other_server))
            }
        }
    }
}

@Composable
private fun ScanButton(label: String, onClick: () -> Unit, busy: Boolean) {
    TextButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(painterResource(DowntifyIcons.QrCode), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(Spacing.sm))
        Text(label)
    }
}

@Composable
private fun Notice(text: String, icon: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun errorText(error: ConnectError?): String? = when (error) {
    null -> null
    ConnectError.InvalidAddress -> stringResource(R.string.connect_error_invalid)
    ConnectError.Unreachable -> stringResource(R.string.connect_error_unreachable)
    is ConnectError.NotDowntify -> "Diagnóstico: ${error.reason}"
    is ConnectError.TooNew -> stringResource(R.string.connect_error_too_new, error.version)
    ConnectError.WrongCode -> stringResource(R.string.pair_error_wrong_code)
    is ConnectError.TooManyAttempts -> stringResource(R.string.pair_error_too_many, error.secondsLeft)
    is ConnectError.PairFailed -> stringResource(R.string.pair_error_failed, error.status)
    ConnectError.OtherServer -> stringResource(R.string.pair_error_other_server)
    ConnectError.NotAPairingCode -> stringResource(R.string.scan_not_downtify)
}

private val previewServers = listOf(
    DiscoveredServer("nas", "cf12", "nas.local", "3.2.0", 1, "http://192.168.1.20:8000"),
    DiscoveredServer("pi", "ab34", "raspberrypi", "3.1.0", 1, "http://192.168.1.44:8000"),
)

@PreviewLightDark
@Composable
private fun ConnectAddressPreview() {
    DowntifyTheme {
        ConnectScreen(
            state = ConnectUiState(
                address = "http://192.168.1.20:8000",
                discovered = previewServers,
                searching = false,
            ),
            onAddressChange = {},
            onPickDiscovered = {},
            onConnect = {},
            onCodeChange = {},
            onPair = {},
            onBackToAddress = {},
            onScan = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun ConnectPairPreview() {
    DowntifyTheme {
        ConnectScreen(
            state = ConnectUiState(
                step = ConnectStep.Pair(
                    "http://192.168.1.20:8000",
                    ServerInfo("cf12", "nas.local", "Downtify", "3.2.0", 1, true, Capabilities()),
                ),
                code = "K7QM-2XPD",
                error = ConnectError.WrongCode,
            ),
            onAddressChange = {},
            onPickDiscovered = {},
            onConnect = {},
            onCodeChange = {},
            onPair = {},
            onBackToAddress = {},
            onScan = {},
        )
    }
}

private const val LOCAL_NETWORK_SDK = 37
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Shows the pairing code uppercase without changing what the keyboard is composing. */
private val UppercaseTransformation = VisualTransformation { text ->
    // Identity offsets need the same length; a few letters (ß → SS) grow when uppercased.
    val upper = text.text.uppercase().takeIf { it.length == text.text.length } ?: text.text
    TransformedText(AnnotatedString(upper), OffsetMapping.Identity)
}
