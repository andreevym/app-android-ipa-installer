package com.example.ipainstaller.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Detects Apple iOS devices connected via USB OTG.
 * Apple vendor ID = 0x05AC (1452 decimal).
 */
class AppleDeviceDetector(
    private val context: Context,
    private val usbManager: UsbManager,
) {
    companion object {
        const val APPLE_VENDOR_ID = 0x05AC
        /** Apple USB Multiplexor interface identifiers. */
        private const val APPLE_MUX_SUBCLASS = 0xFE
        private const val APPLE_MUX_PROTOCOL = 2
        private const val ACTION_USB_PERMISSION = "com.example.ipainstaller.USB_PERMISSION"
    }

    /** Returns currently connected Apple iOS devices (filters out keyboards, mice, etc.). */
    fun findConnectedDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { it.vendorId == APPLE_VENDOR_ID && hasMuxInterface(it) }

    /** B9: Checks if the device has the Apple USB Multiplexor interface (subclass 0xFE, protocol 2). */
    private fun hasMuxInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceSubclass == APPLE_MUX_SUBCLASS && iface.interfaceProtocol == APPLE_MUX_PROTOCOL) {
                return true
            }
        }
        return false
    }

    /** Requests USB permission for a device. Returns true if permission was already granted. */
    fun requestPermission(device: UsbDevice, intent: PendingIntent): Boolean {
        if (usbManager.hasPermission(device)) return true
        usbManager.requestPermission(device, intent)
        return false
    }

    /** Creates a PendingIntent for USB permission requests. */
    fun createPermissionIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
    }

    /** Emits USB attach/detach events for Apple devices as a Flow. */
    fun deviceEvents(): Flow<DeviceEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                } ?: return

                if (device.vendorId != APPLE_VENDOR_ID || !hasMuxInterface(device)) return

                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> trySend(DeviceEvent.Attached(device))
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> trySend(DeviceEvent.Detached(device))
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        trySend(DeviceEvent.PermissionResult(device, granted))
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        awaitClose { context.unregisterReceiver(receiver) }
    }

    sealed interface DeviceEvent {
        data class Attached(val device: UsbDevice) : DeviceEvent
        data class Detached(val device: UsbDevice) : DeviceEvent
        data class PermissionResult(val device: UsbDevice, val granted: Boolean) : DeviceEvent
    }
}
