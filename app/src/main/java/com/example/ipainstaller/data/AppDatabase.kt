package com.example.ipainstaller.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [InstallRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun installHistoryDao(): InstallHistoryDao
}
