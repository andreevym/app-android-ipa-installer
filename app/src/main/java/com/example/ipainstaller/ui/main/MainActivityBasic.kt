package com.example.ipainstaller.ui.main

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.example.ipainstaller.connection.ConnectionManagerCallback
import com.example.ipainstaller.connection.DeviceConnectionManager
import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.InstallState
import com.example.ipainstaller.storage.PairRecordStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full interactive Activity for non-Gradle (manual) builds.
 * Plain Views — no Compose, no Hilt, no AndroidX.
 */
class MainActivityBasic : Activity(), ConnectionManagerCallback {

    private companion object {
        const val REQUEST_PICK_IPA = 1001
        const val ACTION_USB_PERMISSION = "com.example.ipainstaller.USB_PERMISSION"

        const val COLOR_BG = "#1C1B1F"
        const val COLOR_CARD = "#2B2930"
        const val COLOR_TEXT = "#E6E1E5"
        const val COLOR_TEXT_DIM = "#CAC4D0"
        const val COLOR_TEXT_MUTED = "#79747E"
        const val COLOR_GREEN = "#4CAF50"
        const val COLOR_YELLOW = "#FFC107"
        const val COLOR_ORANGE = "#FF9800"
        const val COLOR_RED = "#F44336"
        const val COLOR_ACCENT = "#D0BCFF"
        const val COLOR_BTN = "#4A4458"
        const val COLOR_BTN_DISABLED = "#332D41"
    }

    private lateinit var connectionManager: DeviceConnectionManager

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var deviceInfoText: TextView
    private lateinit var selectIpaBtn: Button
    private lateinit var selectedFileText: TextView
    private lateinit var installBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var progressSection: LinearLayout
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private var selectedIpaData: ByteArray? = null
    private var selectedFileName: String? = null
    private var isPaired = false
    private var currentInstallState: InstallState = InstallState.Idle

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            } ?: return

            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectionManager.onDeviceAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> connectionManager.onDeviceDetached(device)
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        appendLog("USB permission granted")
                        connectionManager.onPermissionGranted(device)
                    } else {
                        appendLog("USB permission denied")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        connectionManager = DeviceConnectionManager(usbManager, this, this, PairRecordStorage(this))

        buildUi()
        registerUsbReceiver()

        // Check if launched by USB intent
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbIntent(intent)
        }

        // Scan for already-connected devices
        connectionManager.startDetection()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbIntent(intent)
        }
    }

    private fun handleUsbIntent(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device != null) {
            connectionManager.onDeviceAttached(device)
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_BG))
            setPadding(32, 48, 32, 32)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "IPA Installer"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        // Connection status card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_CARD))
            setPadding(24, 20, 24, 20)
        }

        statusText = TextView(this).apply {
            text = "No device connected"
            textSize = 16f
            setTextColor(Color.parseColor(COLOR_TEXT_DIM))
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(statusText)

        deviceInfoText = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor(COLOR_TEXT_MUTED))
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
        }
        card.addView(deviceInfoText)

        root.addView(card, lp(topMargin = 0))

        // Select IPA button
        selectIpaBtn = Button(this).apply {
            text = "Select IPA File"
            textSize = 14f
            setTextColor(Color.parseColor(COLOR_TEXT))
            setBackgroundColor(Color.parseColor(COLOR_BTN))
            isEnabled = true
            setPadding(24, 16, 24, 16)
            setOnClickListener { openFilePicker() }
        }
        root.addView(selectIpaBtn, lp(topMargin = 20))

        // Selected file info
        selectedFileText = TextView(this).apply {
            text = "No file selected"
            textSize = 13f
            setTextColor(Color.parseColor(COLOR_TEXT_MUTED))
            setPadding(4, 8, 0, 0)
        }
        root.addView(selectedFileText)

        // Install button
        installBtn = Button(this).apply {
            text = "Install IPA"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(COLOR_BTN_DISABLED))
            isEnabled = false
            setPadding(24, 16, 24, 16)
            setOnClickListener { startInstall() }
        }
        root.addView(installBtn, lp(topMargin = 16))

        // Progress section
        progressSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 12, 0, 0)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        progressSection.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        progressText = TextView(this).apply {
            text = "0%"
            textSize = 12f
            setTextColor(Color.parseColor(COLOR_TEXT_DIM))
            gravity = Gravity.END
            setPadding(0, 4, 0, 0)
        }
        progressSection.addView(progressText)

        root.addView(progressSection)

        // Log area label
        root.addView(TextView(this).apply {
            text = "Log"
            textSize = 12f
            setTextColor(Color.parseColor(COLOR_TEXT_MUTED))
            setPadding(4, 20, 0, 4)
        })

        // Log scroll view
        logScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#151218"))
            setPadding(12, 8, 12, 8)
        }

        logTextView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#A89DB8"))
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod.getInstance()
        }
        logScrollView.addView(logTextView)

        val logParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        root.addView(logScrollView, logParams)

        // Version footer
        root.addView(TextView(this).apply {
            text = "v0.2.0 • Protocol + UI connected"
            textSize = 10f
            setTextColor(Color.parseColor(COLOR_TEXT_MUTED))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        })

        setContentView(root)
    }

    private fun lp(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK_IPA)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_IPA || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        appendLog("File selected: $uri")

        // Read file in background
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")
                val bytes = inputStream.use { it.readBytes() }

                // Extract filename from URI
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "app.ipa"
                val displayName = if (name.endsWith(".ipa", ignoreCase = true)) name
                    else "$name.ipa"

                selectedIpaData = bytes
                selectedFileName = displayName

                runOnUiThread {
                    selectedFileText.text = "$displayName (${bytes.size / 1024}KB)"
                    selectedFileText.setTextColor(Color.parseColor(COLOR_TEXT))
                    updateButtons()
                    appendLog("Loaded $displayName: ${bytes.size} bytes")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("Error reading file: ${e.message}")
                    selectedFileText.text = "Error: ${e.message}"
                    selectedFileText.setTextColor(Color.parseColor(COLOR_RED))
                }
            }
        }.start()
    }

    private fun startInstall() {
        val data = selectedIpaData ?: return
        val name = selectedFileName ?: return
        installBtn.isEnabled = false
        selectIpaBtn.isEnabled = false
        connectionManager.installIpa(data, name)
    }

    private fun updateButtons() {
        val isInstalling = currentInstallState is InstallState.Uploading ||
            currentInstallState is InstallState.Installing
        val canSelect = !isInstalling
        val canInstall = isPaired && selectedIpaData != null &&
            currentInstallState is InstallState.Idle

        selectIpaBtn.isEnabled = canSelect
        selectIpaBtn.setBackgroundColor(
            Color.parseColor(if (canSelect) COLOR_BTN else COLOR_BTN_DISABLED)
        )

        installBtn.isEnabled = canInstall
        installBtn.setBackgroundColor(
            Color.parseColor(if (canInstall) COLOR_GREEN else COLOR_BTN_DISABLED)
        )
    }

    // ── ConnectionManagerCallback ────────────────────────────────

    override fun onConnectionStateChanged(state: ConnectionState) {
        runOnUiThread {
            isPaired = state is ConnectionState.Paired
            when (state) {
                is ConnectionState.Disconnected -> {
                    statusText.text = "No device connected"
                    statusText.setTextColor(Color.parseColor(COLOR_TEXT_DIM))
                    deviceInfoText.visibility = View.GONE
                }
                is ConnectionState.UsbConnected -> {
                    statusText.text = "USB connected — initializing..."
                    statusText.setTextColor(Color.parseColor(COLOR_YELLOW))
                    deviceInfoText.visibility = View.GONE
                }
                is ConnectionState.Pairing -> {
                    statusText.text = "Pairing — tap 'Trust' on iPhone..."
                    statusText.setTextColor(Color.parseColor(COLOR_ORANGE))
                }
                is ConnectionState.Paired -> {
                    val info = state.deviceInfo
                    statusText.text = "Connected"
                    statusText.setTextColor(Color.parseColor(COLOR_GREEN))
                    deviceInfoText.text = "${info.deviceName}\niOS ${info.productVersion} (${info.productType})\nUDID: ${info.udid}"
                    deviceInfoText.visibility = View.VISIBLE
                }
                is ConnectionState.Error -> {
                    statusText.text = "Error: ${state.message}"
                    statusText.setTextColor(Color.parseColor(COLOR_RED))
                }
            }
            updateButtons()
        }
    }

    override fun onInstallStateChanged(state: InstallState) {
        runOnUiThread {
            currentInstallState = state
            when (state) {
                is InstallState.Idle -> {
                    progressSection.visibility = View.GONE
                }
                is InstallState.Uploading -> {
                    progressSection.visibility = View.VISIBLE
                    val pct = (state.progress * 100).toInt()
                    progressBar.progress = pct
                    progressText.text = "Uploading: $pct%"
                }
                is InstallState.Installing -> {
                    progressSection.visibility = View.VISIBLE
                    val pct = (state.progress * 100).toInt()
                    progressBar.progress = pct
                    progressText.text = "${state.status}: $pct%"
                }
                is InstallState.Success -> {
                    progressSection.visibility = View.VISIBLE
                    progressBar.progress = 100
                    progressText.text = "Installation complete!"
                    progressText.setTextColor(Color.parseColor(COLOR_GREEN))
                }
                is InstallState.Failed -> {
                    progressSection.visibility = View.VISIBLE
                    progressText.text = "Failed: ${state.error}"
                    progressText.setTextColor(Color.parseColor(COLOR_RED))
                }
            }
            updateButtons()
        }
    }

    override fun onLog(message: String) {
        runOnUiThread { appendLog(message) }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] $message\n"
        logTextView.append(line)
        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        connectionManager.destroy()
    }
}
