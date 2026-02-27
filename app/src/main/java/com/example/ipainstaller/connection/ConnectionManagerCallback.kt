package com.example.ipainstaller.connection

import com.example.ipainstaller.model.ConnectionState
import com.example.ipainstaller.model.InstallState

interface ConnectionManagerCallback {
    fun onConnectionStateChanged(state: ConnectionState)
    fun onInstallStateChanged(state: InstallState)
    fun onLog(message: String)
}
