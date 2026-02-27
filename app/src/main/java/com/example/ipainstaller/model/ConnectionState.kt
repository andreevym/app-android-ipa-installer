package com.example.ipainstaller.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object UsbConnected : ConnectionState
    data object Pairing : ConnectionState
    data class Paired(val deviceInfo: DeviceInfo) : ConnectionState
    data class Error(val message: String) : ConnectionState
}
