package com.example.ipainstaller.connection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.example.ipainstaller.crypto.PairingCrypto
import com.example.ipainstaller.crypto.TlsTransport
import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.DeviceInfo
import com.example.ipainstaller.model.InstallState
import com.example.ipainstaller.model.PairRecord
import com.example.ipainstaller.protocol.afc.AfcClient
import com.example.ipainstaller.protocol.installproxy.InstallationProxyClient
import com.example.ipainstaller.protocol.lockdownd.LockdownClient
import com.example.ipainstaller.protocol.usbmuxd.MuxMessage
import com.example.ipainstaller.storage.PairRecordStorage
import com.example.ipainstaller.usb.AppleDeviceDetector
import com.example.ipainstaller.usb.UsbMuxConnection
import com.example.ipainstaller.usb.UsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the full pipeline: USB detection → usbmuxd → lockdownd → AFC → install.
 *
 * Each phase opens a fresh UsbTransport because after a usbmuxd Connect the
 * transport is dedicated to a single TCP tunnel.
 */
class DeviceConnectionManager(
    private val usbManager: UsbManager,
    private val context: Context,
    private val callback: ConnectionManagerCallback,
    private val pairRecordStorage: PairRecordStorage,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val detector = AppleDeviceDetector(context, usbManager)

    private var connectionState: ConnectionState = ConnectionState.Disconnected
    private var installState: InstallState = InstallState.Idle

    private var currentDevice: UsbDevice? = null
    private var muxDeviceId: Int = -1
    private var deviceInfo: DeviceInfo? = null
    private var pairRecord: PairRecord? = null
    private var afcPort: Int = -1
    private var proxyPort: Int = -1
    private var connectJob: Job? = null

    private fun updateConnection(state: ConnectionState) {
        connectionState = state
        callback.onConnectionStateChanged(state)
    }

    private fun updateInstall(state: InstallState) {
        installState = state
        callback.onInstallStateChanged(state)
    }

    private fun log(msg: String) = callback.onLog(msg)

    /** Scans for connected Apple devices and starts the connection pipeline. */
    fun startDetection() {
        val devices = detector.findConnectedDevices()
        if (devices.isEmpty()) {
            log("No Apple devices found")
            updateConnection(ConnectionState.Disconnected)
            return
        }

        val device = devices.first()
        currentDevice = device
        log("Found Apple device: ${device.deviceName} (VID=${device.vendorId}, PID=${device.productId})")

        if (!usbManager.hasPermission(device)) {
            log("Requesting USB permission...")
            usbManager.requestPermission(device, detector.createPermissionIntent())
            return
        }

        startConnectionPipeline(device)
    }

    /** Called when USB permission is granted. */
    fun onPermissionGranted(device: UsbDevice) {
        currentDevice = device
        startConnectionPipeline(device)
    }

    /** Called when a USB device is attached. */
    fun onDeviceAttached(device: UsbDevice) {
        if (device.vendorId != AppleDeviceDetector.APPLE_VENDOR_ID) return
        currentDevice = device
        log("Apple device attached: ${device.deviceName}")
        updateConnection(ConnectionState.UsbConnected)

        if (usbManager.hasPermission(device)) {
            startConnectionPipeline(device)
        } else {
            log("Requesting USB permission...")
            usbManager.requestPermission(device, detector.createPermissionIntent())
        }
    }

    /** Called when a USB device is detached. */
    fun onDeviceDetached(device: UsbDevice) {
        if (device.vendorId != AppleDeviceDetector.APPLE_VENDOR_ID) return
        log("Apple device detached")
        disconnect()
    }

    private fun startConnectionPipeline(device: UsbDevice) {
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                updateConnection(ConnectionState.UsbConnected)
                phaseDiscoverAndPair(device)
            } catch (e: Exception) {
                log("Connection failed: ${e.message}")
                updateConnection(ConnectionState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Phase 1: Discover device via usbmuxd, connect to lockdownd,
     * query info, pair, start session, upgrade TLS, get service ports.
     */
    private suspend fun phaseDiscoverAndPair(device: UsbDevice) {
        log("Opening USB transport...")
        val transport = UsbTransport.open(usbManager, device)
        try {
            val mux = UsbMuxConnection(transport)

            // Discover device via ListDevices
            log("Sending ListDevices...")
            val listResponse = mux.sendMessage(MuxMessage.ListDevices)

            when (listResponse) {
                is MuxMessage.DeviceList -> {
                    if (listResponse.devices.isEmpty()) {
                        throw Exception("No devices in ListDevices response")
                    }
                    val dev = listResponse.devices.first()
                    muxDeviceId = dev.deviceId
                    log("Device found: id=${dev.deviceId}, serial=${dev.serialNumber}")
                }
                is MuxMessage.Result -> {
                    if (!listResponse.isSuccess) {
                        throw Exception("ListDevices failed: result=${listResponse.number}")
                    }
                    // Some firmware returns Result(0) — use deviceId=0
                    muxDeviceId = 0
                    log("ListDevices returned Result(0), using deviceId=0")
                }
                else -> {
                    throw Exception("Unexpected ListDevices response: ${listResponse::class.simpleName}")
                }
            }

            // Connect to lockdownd (port 62078)
            log("Connecting to lockdownd (port ${LockdownClient.LOCKDOWN_PORT})...")
            val connectResult = mux.connect(muxDeviceId, LockdownClient.LOCKDOWN_PORT)
            if (connectResult is MuxMessage.Result && !connectResult.isSuccess) {
                throw Exception("usbmuxd Connect failed: result=${connectResult.number}")
            }
            log("Connected to lockdownd")

            // Transport now tunnels to lockdownd
            val rawTransport = mux.getTransport()
            val lockdown = LockdownClient(
                readFn = { size -> rawTransport.readExact(size) },
                writeFn = { data -> rawTransport.write(data) },
            )

            // Query device type
            updateConnection(ConnectionState.Pairing)
            log("Querying device type...")
            val type = lockdown.queryType()
            log("Device type: $type")

            // Get device info
            log("Getting device info...")
            val info = lockdown.getDeviceInfo()
            deviceInfo = info
            log("Device: ${info.deviceName} (${info.productType}, iOS ${info.productVersion})")

            // Check for stored pair record
            val storedRecord = pairRecordStorage.load(info.udid)
            if (storedRecord != null) {
                log("Using stored pair record for ${info.udid}")
                pairRecord = storedRecord
            } else {
                // Get device public key for certificate generation
                log("Getting device public key...")
                val pubKeyResp = lockdown.getValue(key = "DevicePublicKey")
                val pubKeyNSData = pubKeyResp["Value"] as? NSData
                    ?: throw Exception("No DevicePublicKey in response")
                val devicePublicKeyDer = pubKeyNSData.bytes()

                log("Generating pair record with device certificate...")
                pairRecord = PairingCrypto.generatePairRecordWithDeviceKey(devicePublicKeyDer)

                log("Sending Pair request (tap 'Trust' on iPhone)...")
                val pairResult = lockdown.pair(pairRecord!!)
                log("Pair response: $pairResult")

                // Save pair record for future connections
                pairRecordStorage.save(info.udid, pairRecord!!)
                log("Pair record saved for ${info.udid}")
            }

            // Start session
            log("Starting session...")
            val sessionResult = lockdown.startSession(
                hostId = pairRecord!!.hostId,
                systemBuid = pairRecord!!.systemBuid,
            )
            log("Session response: $sessionResult")

            // Check if TLS upgrade is needed
            val enableSSL = (sessionResult["EnableSessionSSL"] as? NSNumber)?.boolValue() ?: false
            if (enableSSL) {
                log("Upgrading to TLS...")
                val tlsTransport = TlsTransport(rawTransport, pairRecord!!)
                withContext(Dispatchers.IO) {
                    lockdown.upgradeTls(tlsTransport)
                }
                log("TLS handshake complete")
            }

            // Start services (now works over TLS)
            log("Starting AFC service...")
            val afcDescriptor = lockdown.startService(AfcClient.SERVICE_NAME)
            afcPort = afcDescriptor.port
            log("AFC service on port $afcPort (ssl=${afcDescriptor.enableSSL})")

            log("Starting installation_proxy service...")
            val proxyDescriptor = lockdown.startService(InstallationProxyClient.SERVICE_NAME)
            proxyPort = proxyDescriptor.port
            log("installation_proxy on port $proxyPort (ssl=${proxyDescriptor.enableSSL})")

            updateConnection(ConnectionState.Paired(info))
        } finally {
            transport.close()
        }
    }

    /**
     * Phase 2 + 3: Upload IPA via AFC, then install via installation_proxy.
     */
    fun installIpa(ipaData: ByteArray, fileName: String) {
        if (currentDevice == null) {
            log("No device connected")
            return
        }
        if (afcPort <= 0 || proxyPort <= 0) {
            log("Service ports not available")
            updateInstall(InstallState.Failed("Service ports not available"))
            return
        }

        scope.launch {
            try {
                phaseUploadIpa(ipaData, fileName)
                phaseInstallIpa(fileName)
            } catch (e: Exception) {
                log("Install failed: ${e.message}")
                updateInstall(InstallState.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    /** Phase 2: Upload IPA to /PublicStaging/ via AFC. */
    private suspend fun phaseUploadIpa(ipaData: ByteArray, fileName: String) {
        val device = currentDevice ?: throw Exception("No device")
        updateInstall(InstallState.Uploading(0f))
        log("Opening transport for AFC (port $afcPort)...")

        val transport = UsbTransport.open(usbManager, device)
        try {
            val mux = UsbMuxConnection(transport)
            val connectResult = mux.connect(muxDeviceId, afcPort)
            if (connectResult is MuxMessage.Result && !connectResult.isSuccess) {
                throw Exception("AFC connect failed: result=${(connectResult).number}")
            }

            val rawTransport = mux.getTransport()
            val afc = AfcClient(
                readFn = { size -> rawTransport.readExact(size) },
                writeFn = { data -> rawTransport.write(data) },
            )

            log("Creating /PublicStaging/ directory...")
            try {
                afc.makeDirectory("/PublicStaging")
            } catch (e: Exception) {
                log("makeDirectory warning: ${e.message} (may already exist)")
            }

            val remotePath = "/PublicStaging/$fileName"
            log("Uploading $fileName (${ipaData.size / 1024}KB) to $remotePath...")
            afc.uploadFile(remotePath, ipaData) { written, total ->
                val progress = written.toFloat() / total.toFloat()
                updateInstall(InstallState.Uploading(progress))
            }
            log("Upload complete")
        } finally {
            transport.close()
        }
    }

    /** Phase 3: Install uploaded IPA via installation_proxy. */
    private suspend fun phaseInstallIpa(fileName: String) {
        val device = currentDevice ?: throw Exception("No device")
        updateInstall(InstallState.Installing(0f, "Starting install..."))
        log("Opening transport for installation_proxy (port $proxyPort)...")

        val transport = UsbTransport.open(usbManager, device)
        try {
            val mux = UsbMuxConnection(transport)
            val connectResult = mux.connect(muxDeviceId, proxyPort)
            if (connectResult is MuxMessage.Result && !connectResult.isSuccess) {
                throw Exception("installation_proxy connect failed: result=${(connectResult).number}")
            }

            val rawTransport = mux.getTransport()
            val proxy = InstallationProxyClient(
                readFn = { size -> rawTransport.readExact(size) },
                writeFn = { data -> rawTransport.write(data) },
            )

            val remotePath = "/PublicStaging/$fileName"
            log("Installing $remotePath...")
            proxy.install(remotePath) { status, percent ->
                log("Install progress: $status ($percent%)")
                updateInstall(InstallState.Installing(percent / 100f, status))
            }

            log("Installation complete!")
            updateInstall(InstallState.Success)
        } finally {
            transport.close()
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        currentDevice = null
        muxDeviceId = -1
        deviceInfo = null
        pairRecord = null
        afcPort = -1
        proxyPort = -1
        updateConnection(ConnectionState.Disconnected)
        updateInstall(InstallState.Idle)
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
