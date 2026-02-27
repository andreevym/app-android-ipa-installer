package com.example.ipainstaller.ui.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ipainstaller.R
import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.InstallState
import com.example.ipainstaller.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val installState by viewModel.installState.collectAsState()
    val selectedIpa by viewModel.selectedIpa.collectAsState()

    val ipaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.onIpaSelected(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("IPA Installer") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Device status card
            DeviceStatusCard(connectionState)

            Spacer(modifier = Modifier.height(8.dp))

            // IPA file selector
            OutlinedButton(
                onClick = { ipaPickerLauncher.launch(arrayOf("application/octet-stream")) },
                enabled = connectionState is ConnectionState.Paired,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selectedIpa?.lastPathSegment ?: stringResource(R.string.select_ipa))
            }

            // Install button
            Button(
                onClick = { viewModel.installIpa() },
                enabled = selectedIpa != null
                        && connectionState is ConnectionState.Paired
                        && installState is InstallState.Idle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.install))
            }

            // Install progress
            InstallProgress(installState, onDismiss = { viewModel.resetInstallState() })
        }
    }
}

@Composable
private fun DeviceStatusCard(state: ConnectionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                }
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
