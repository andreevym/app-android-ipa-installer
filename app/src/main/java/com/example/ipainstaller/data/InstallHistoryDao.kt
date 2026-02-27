package com.example.ipainstaller.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InstallHistoryDao {
    @Insert
    suspend fun insert(record: InstallRecord)

    @Query("SELECT * FROM install_history ORDER BY timestamp DESC LIMIT 50")
    fun getAll(): Flow<List<InstallRecord>>

    @Query("DELETE FROM install_history WHERE id NOT IN (SELECT id FROM install_history ORDER BY timestamp DESC LIMIT 100)")
    suspend fun deleteOld()
}
