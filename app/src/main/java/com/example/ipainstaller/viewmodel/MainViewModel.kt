package com.example.ipainstaller.viewmodel

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.InstallState
import com.example.ipainstaller.usb.AppleDeviceDetector
import com.example.ipainstaller.usb.UsbMuxConnection
import com.example.ipainstaller.usb.UsbTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val deviceDetector: AppleDeviceDetector,
    private val usbManager: UsbManager,
) : ViewModel() {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _selectedIpa = MutableStateFlow<Uri?>(null)
    val selectedIpa: StateFlow<Uri?> = _selectedIpa.asStateFlow()

    private var currentDevice: UsbDevice? = null
    private var muxConnection: UsbMuxConnection? = null

    init {
        observeDeviceEvents()
    }

    private fun observeDeviceEvents() {
        viewModelScope.launch {
            deviceDetector.deviceEvents().collect { event ->
                when (event) {
                    is AppleDeviceDetector.DeviceEvent.Attached -> {
                        currentDevice = event.device
                        _connectionState.value = ConnectionState.UsbConnected
                        deviceDetector.requestPermission(
                            event.device,
                            deviceDetector.createPermissionIntent(),
                        )
                    }
                    is AppleDeviceDetector.DeviceEvent.Detached -> {
                        disconnect()
                    }
                    is AppleDeviceDetector.DeviceEvent.PermissionResult -> {
                        if (event.granted) {
                            connectToDevice(event.device)
                        } else {
                            _connectionState.value = ConnectionState.Error("USB permission denied")
                        }
                    }
                }
            }
        }
    }

    /** Attempts initial scan for already-connected devices. */
    fun scanForDevices() {
        val devices = deviceDetector.findConnectedDevices()
        if (devices.isNotEmpty()) {
            val device = devices.first()
            currentDevice = device
            _connectionState.value = ConnectionState.UsbConnected
            deviceDetector.requestPermission(device, deviceDetector.createPermissionIntent())
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        viewModelScope.launch {
            try {
                _connectionState.value = ConnectionState.Pairing
                val transport = UsbTransport.open(usbManager, device)
                muxConnection = UsbMuxConnection(transport)

                // TODO: Perform usbmuxd handshake, pairing, and get device info
                // For now, move to Pairing state — full implementation will query
                // lockdownd for device info and handle the Trust dialog

            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun onIpaSelected(uri: Uri) {
        _selectedIpa.value = uri
    }

    fun installIpa() {
        val uri = _selectedIpa.value ?: return
        val connection = muxConnection ?: return

        viewModelScope.launch {
            try {
                _installState.value = InstallState.Uploading(0f)

                // TODO: Full install pipeline
                // 1. Start lockdownd session
                // 2. Start AFC service
                // 3. Upload IPA via AFC to /PublicStaging/
                // 4. Start installation_proxy service
                // 5. Send Install command with progress tracking

                _installState.value = InstallState.Installing(0f, "Preparing…")

                // Placeholder for actual install logic
                _installState.value = InstallState.Success

            } catch (e: Exception) {
                _installState.value = InstallState.Failed(e.message ?: "Installation failed")
            }
        }
    }

    fun resetInstallState() {
        _installState.value = InstallState.Idle
    }

    private fun disconnect() {
        muxConnection?.close()
        muxConnection = null
        currentDevice = null
        _connectionState.value = ConnectionState.Disconnected
        _installState.value = InstallState.Idle
        _selectedIpa.value = null
    }

    override fun onCleared() {
        super.onCleared()
        muxConnection?.close()
    }
}
