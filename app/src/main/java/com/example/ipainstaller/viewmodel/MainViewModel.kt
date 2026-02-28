package com.example.ipainstaller.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ipainstaller.connection.ConnectionManagerCallback
import com.example.ipainstaller.connection.DeviceConnectionManager
import com.example.ipainstaller.data.InstallHistoryDao
import com.example.ipainstaller.data.InstallRecord
import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.InstallState
import com.example.ipainstaller.model.IpaInfo
import com.example.ipainstaller.notification.InstallNotificationHelper
import com.example.ipainstaller.storage.PairRecordStorage
import com.example.ipainstaller.usb.AppleDeviceDetector
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val deviceDetector: AppleDeviceDetector,
    private val usbManager: UsbManager,
    private val contentResolver: ContentResolver,
    private val installHistoryDao: InstallHistoryDao,
    private val notificationHelper: InstallNotificationHelper,
    private val pairRecordStorage: PairRecordStorage,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _selectedIpa = MutableStateFlow<Uri?>(null)
    val selectedIpa: StateFlow<Uri?> = _selectedIpa.asStateFlow()

    private val _ipaInfo = MutableStateFlow<IpaInfo?>(null)
    val ipaInfo: StateFlow<IpaInfo?> = _ipaInfo.asStateFlow()

    val installHistory = installHistoryDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // B11: Protect mutable device state with Mutex to prevent data races
    private val deviceMutex = Mutex()
    private var currentDevice: UsbDevice? = null
    private var connectionManager: DeviceConnectionManager? = null

    init {
        observeDeviceEvents()
        notificationHelper.createChannel()
    }

    private fun observeDeviceEvents() {
        viewModelScope.launch {
            deviceDetector.deviceEvents().collect { event ->
                when (event) {
                    is AppleDeviceDetector.DeviceEvent.Attached -> {
                        deviceMutex.withLock { currentDevice = event.device }
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
            viewModelScope.launch {
                deviceMutex.withLock { currentDevice = device }
            }
            _connectionState.value = ConnectionState.UsbConnected
            deviceDetector.requestPermission(device, deviceDetector.createPermissionIntent())
        }
    }

    /** B10: Called when a USB device is attached via intent. */
    fun onUsbDeviceAttached(device: UsbDevice) {
        viewModelScope.launch {
            deviceMutex.withLock { currentDevice = device }
            _connectionState.value = ConnectionState.UsbConnected
            deviceDetector.requestPermission(device, deviceDetector.createPermissionIntent())
        }
    }

    /** U4: Reconnect after error. */
    fun reconnect() {
        _connectionState.value = ConnectionState.Disconnected
        scanForDevices()
    }

    private fun connectToDevice(device: UsbDevice) {
        viewModelScope.launch {
            try {
                _connectionState.value = ConnectionState.Pairing

                val manager = DeviceConnectionManager(
                    usbManager = usbManager,
                    context = appContext,
                    callback = object : ConnectionManagerCallback {
                        override fun onConnectionStateChanged(state: ConnectionState) {
                            _connectionState.value = state
                        }

                        override fun onInstallStateChanged(state: InstallState) {
                            _installState.value = state
                        }

                        override fun onLog(message: String) {
                            // Log messages available via callback
                        }
                    },
                    pairRecordStorage = pairRecordStorage,
                )

                deviceMutex.withLock {
                    connectionManager?.destroy()
                    connectionManager = manager
                }

                manager.onPermissionGranted(device)
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            }
        }
    }

    /** U5: Parse IPA info after file selection. */
    fun onIpaSelected(uri: Uri) {
        _selectedIpa.value = uri
        viewModelScope.launch {
            _ipaInfo.value = parseIpaInfo(uri)
        }
    }

    private suspend fun parseIpaInfo(uri: Uri): IpaInfo? = withContext(Dispatchers.IO) {
        try {
            val displayName = contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "Unknown"

            val size = contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            } ?: -1L

            var bundleId: String? = null
            var bundleVersion: String? = null

            contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name.matches(Regex("Payload/[^/]+\\.app/Info\\.plist"))) {
                            val plistBytes = zip.readBytes()
                            val plist = PropertyListParser.parse(plistBytes) as? NSDictionary
                            bundleId = plist?.get("CFBundleIdentifier")?.toJavaObject()?.toString()
                            bundleVersion = plist?.get("CFBundleShortVersionString")?.toJavaObject()?.toString()
                            break
                        }
                        entry = zip.nextEntry
                    }
                }
            }

            IpaInfo(displayName, size, bundleId, bundleVersion)
        } catch (_: Exception) {
            null
        }
    }

    fun installIpa() {
        val uri = _selectedIpa.value ?: return

        viewModelScope.launch {
            val manager = deviceMutex.withLock { connectionManager } ?: return@launch
            val ipaName = _ipaInfo.value?.displayName ?: "Unknown IPA"
            val deviceName = (_connectionState.value as? ConnectionState.Paired)
                ?.deviceInfo?.deviceName ?: "Unknown"

            try {
                _installState.value = InstallState.Uploading(0f)

                // Read IPA data from Uri
                val ipaData = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read IPA file")
                }

                val fileName = _ipaInfo.value?.displayName ?: "app.ipa"
                manager.installIpa(ipaData, fileName)

                // U9: Notification
                notificationHelper.showInstallComplete(true, ipaName)

                // U10: Record history
                installHistoryDao.insert(
                    InstallRecord(
                        ipaName = ipaName,
                        bundleId = _ipaInfo.value?.bundleId,
                        deviceName = deviceName,
                        timestamp = System.currentTimeMillis(),
                        success = true,
                        errorMessage = null,
                    )
                )
                installHistoryDao.deleteOld()

            } catch (e: Exception) {
                val error = e.message ?: "Installation failed"
                _installState.value = InstallState.Failed(error)

                notificationHelper.showInstallComplete(false, ipaName)

                installHistoryDao.insert(
                    InstallRecord(
                        ipaName = ipaName,
                        bundleId = _ipaInfo.value?.bundleId,
                        deviceName = deviceName,
                        timestamp = System.currentTimeMillis(),
                        success = false,
                        errorMessage = error,
                    )
                )
                installHistoryDao.deleteOld()
            }
        }
    }

    fun resetInstallState() {
        _installState.value = InstallState.Idle
    }

    private fun disconnect() {
        viewModelScope.launch {
            deviceMutex.withLock {
                connectionManager?.destroy()
                connectionManager = null
                currentDevice = null
            }
            _connectionState.value = ConnectionState.Disconnected
            _installState.value = InstallState.Idle
            _selectedIpa.value = null
            _ipaInfo.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager?.destroy()
    }
}
