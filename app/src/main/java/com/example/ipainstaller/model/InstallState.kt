package com.example.ipainstaller.model

sealed interface InstallState {
    data object Idle : InstallState
    data class Uploading(val progress: Float) : InstallState
    data class Installing(val progress: Float, val status: String) : InstallState
    data object Success : InstallState
    data class Failed(val error: String) : InstallState
}
