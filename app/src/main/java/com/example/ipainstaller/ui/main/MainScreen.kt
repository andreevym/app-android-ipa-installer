package com.example.ipainstaller.ui.main

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ipainstaller.R
import com.example.ipainstaller.data.InstallRecord
import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.DeviceInfo
import com.example.ipainstaller.model.InstallState
import com.example.ipainstaller.model.IpaInfo
import com.example.ipainstaller.ui.theme.IpaInstallerTheme
import com.example.ipainstaller.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val installState by viewModel.installState.collectAsState()
    val selectedIpa by viewModel.selectedIpa.collectAsState()
    val ipaInfo by viewModel.ipaInfo.collectAsState()
    val installHistory by viewModel.installHistory.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // U1: File picker with extension validation
    val invalidFileMessage = stringResource(R.string.invalid_file_type)
    val ipaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val name = context.contentResolver.query(
                it, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (name?.endsWith(".ipa", ignoreCase = true) == true) {
                viewModel.onIpaSelected(it)
            } else {
                scope.launch { snackbarHostState.showSnackbar(invalidFileMessage) }
            }
        }
    }

    MainScreenContent(
        connectionState = connectionState,
        installState = installState,
        ipaInfo = ipaInfo,
        installHistory = installHistory,
        snackbarHostState = snackbarHostState,
        isPaired = connectionState is ConnectionState.Paired,
        canInstall = selectedIpa != null
                && connectionState is ConnectionState.Paired
                && installState is InstallState.Idle,
        onSelectIpa = { ipaPickerLauncher.launch(arrayOf("*/*")) },
        onInstall = { viewModel.installIpa() },
        onDismiss = { viewModel.resetInstallState() },
        onReconnect = { viewModel.reconnect() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    connectionState: ConnectionState,
    installState: InstallState,
    ipaInfo: IpaInfo?,
    installHistory: List<InstallRecord>,
    snackbarHostState: SnackbarHostState,
    isPaired: Boolean,
    canInstall: Boolean,
    onSelectIpa: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    onReconnect: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("IPA Installer") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // U3: LazyColumn for scrollable content + U10 history
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(0.dp))
            }

            // Device status card
            item {
                DeviceStatusCard(connectionState, onReconnect)
            }

            // IPA file selector
            item {
                OutlinedButton(
                    onClick = onSelectIpa,
                    enabled = isPaired,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(ipaInfo?.displayName ?: stringResource(R.string.select_ipa))
                }
            }

            // U5: IPA info card
            if (ipaInfo != null) {
                item {
                    IpaInfoCard(ipaInfo)
                }
            }

            // Install button
            item {
                Button(
                    onClick = onInstall,
                    enabled = canInstall,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.install))
                }
            }

            // Install progress
            if (installState !is InstallState.Idle) {
                item {
                    InstallProgress(installState, onDismiss)
                }
            }

            // U10: Install history
            if (installHistory.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.install_history),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(installHistory, key = { it.id }) { record ->
                    HistoryItem(record)
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DeviceStatusCard(state: ConnectionState, onReconnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is ConnectionState.Disconnected -> {
                    Icon(
                        Icons.Default.UsbOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.no_device),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ConnectionState.UsbConnected -> {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.usb_connected_requesting_permission))
                }
                is ConnectionState.Pairing -> {
                    Icon(
                        Icons.Default.PhoneIphone,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.pairing_required))
                }
                is ConnectionState.Paired -> {
                    Icon(
                        Icons.Default.PhoneIphone,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.device_connected, state.deviceInfo.deviceName),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.ios_version, state.deviceInfo.productVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ConnectionState.Error -> {
                    Icon(
                        Icons.Default.UsbOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // U4: Reconnect button
                    OutlinedButton(onClick = onReconnect) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.reconnect))
                    }
                }
            }
        }
    }
}

/** U5: Display parsed IPA file information. */
@Composable
private fun IpaInfoCard(info: IpaInfo) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (info.sizeBytes > 0) {
                Text(
                    stringResource(R.string.ipa_size, Formatter.formatFileSize(context, info.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            info.bundleId?.let {
                Text(
                    stringResource(R.string.ipa_bundle_id, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            info.bundleVersion?.let {
                Text(
                    stringResource(R.string.ipa_version, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InstallProgress(state: InstallState, onDismiss: () -> Unit) {
    when (state) {
        is InstallState.Idle -> {}
        is InstallState.Uploading -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.uploading_ipa))
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is InstallState.Installing -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.status)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is InstallState.Success -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.install_success),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        }
        is InstallState.Failed -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

/** U10: Single history list item. */
@Composable
private fun HistoryItem(record: InstallRecord) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (record.success) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (record.success) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                record.ipaName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${record.deviceName} — ${dateFormat.format(Date(record.timestamp))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── U6: Compose Previews ──

private val sampleDeviceInfo = DeviceInfo(
    udid = "00001111-AAAA2222BBBB3333",
    deviceName = "Yury's iPhone",
    productType = "iPhone15,2",
    productVersion = "17.4.1",
    buildVersion = "21E237",
)

private val sampleIpaInfo = IpaInfo(
    displayName = "MyApp.ipa",
    sizeBytes = 52_428_800,
    bundleId = "com.example.myapp",
    bundleVersion = "1.2.3",
)

private val sampleHistory = listOf(
    InstallRecord(1, "App1.ipa", "com.app1", "iPhone", 1709136000000, true, null),
    InstallRecord(2, "App2.ipa", "com.app2", "iPhone", 1709049600000, false, "Signing error"),
)

@Preview(showBackground = true, name = "Disconnected")
@Composable
private fun PreviewDisconnected() {
    IpaInstallerTheme {
        MainScreenContent(
            connectionState = ConnectionState.Disconnected,
            installState = InstallState.Idle,
            ipaInfo = null,
            installHistory = emptyList(),
            snackbarHostState = SnackbarHostState(),
            isPaired = false,
            canInstall = false,
            onSelectIpa = {},
            onInstall = {},
            onDismiss = {},
            onReconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Paired + IPA Selected")
@Composable
private fun PreviewPaired() {
    IpaInstallerTheme {
        MainScreenContent(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            installState = InstallState.Idle,
            ipaInfo = sampleIpaInfo,
            installHistory = sampleHistory,
            snackbarHostState = SnackbarHostState(),
            isPaired = true,
            canInstall = true,
            onSelectIpa = {},
            onInstall = {},
            onDismiss = {},
            onReconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Error State")
@Composable
private fun PreviewError() {
    IpaInstallerTheme {
        MainScreenContent(
            connectionState = ConnectionState.Error("USB permission denied"),
            installState = InstallState.Idle,
            ipaInfo = null,
            installHistory = emptyList(),
            snackbarHostState = SnackbarHostState(),
            isPaired = false,
            canInstall = false,
            onSelectIpa = {},
            onInstall = {},
            onDismiss = {},
            onReconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Uploading")
@Composable
private fun PreviewUploading() {
    IpaInstallerTheme {
        MainScreenContent(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            installState = InstallState.Uploading(0.45f),
            ipaInfo = sampleIpaInfo,
            installHistory = emptyList(),
            snackbarHostState = SnackbarHostState(),
            isPaired = true,
            canInstall = false,
            onSelectIpa = {},
            onInstall = {},
            onDismiss = {},
            onReconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Install Success")
@Composable
private fun PreviewInstallSuccess() {
    IpaInstallerTheme {
        MainScreenContent(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            installState = InstallState.Success,
            ipaInfo = sampleIpaInfo,
            installHistory = sampleHistory,
            snackbarHostState = SnackbarHostState(),
            isPaired = true,
            canInstall = false,
            onSelectIpa = {},
            onInstall = {},
            onDismiss = {},
            onReconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Install Failed")
@Composable
private fun PreviewInstallFailed() {
    IpaInstallerTheme {
        MainScreenContent(
            connectionState = ConnectionState.Paired(sampleDeviceInfo),
            installState = InstallState.Failed("Signing verification failed"),
            ipaInfo = sampleIpaInfo,
            installHistory = sampleHistory,
            snackbarHostState = SnackbarHostState(),
            isPaired = true,
            canInstall = false,
            onSelectIpa = {},
            onInstall = {},
            onDismiss = {},
            onReconnect = {},
        )
    }
}
