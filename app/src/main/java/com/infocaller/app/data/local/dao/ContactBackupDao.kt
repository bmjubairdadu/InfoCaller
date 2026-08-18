package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.ContactBackupEntity

@Dao
interface ContactBackupDao {
    @Query("SELECT * FROM contact_backups WHERE contactId = :contactId")
    suspend fun getBackup(contactId: Long): ContactBackupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBackup(backup: ContactBackupEntity)

    @Delete
    suspend fun deleteBackup(backup: ContactBackupEntity)

    @Query("DELETE FROM contact_backups WHERE contactId = :contactId")
    suspend fun deleteBackupById(contactId: Long)
}
