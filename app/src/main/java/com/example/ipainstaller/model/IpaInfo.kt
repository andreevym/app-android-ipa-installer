package com.example.ipainstaller.model

data class IpaInfo(
    val displayName: String,
    val sizeBytes: Long,
    val bundleId: String?,
    val bundleVersion: String?,
)
