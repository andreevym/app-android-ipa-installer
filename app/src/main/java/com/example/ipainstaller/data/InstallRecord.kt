package com.example.ipainstaller.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "install_history")
data class InstallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ipaName: String,
    val bundleId: String?,
    val deviceName: String,
    val timestamp: Long,
    val success: Boolean,
    val errorMessage: String?,
)
