package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.LocalContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalContactDao {
    @Query("SELECT * FROM local_contacts ORDER BY displayName ASC")
    fun getAllContacts(): Flow<List<LocalContactEntity>>

    @Transaction
    @Query("SELECT * FROM local_contacts ORDER BY displayName ASC")
    fun getAllEnrichedContacts(): Flow<List<com.infocaller.app.data.local.model.EnrichedContact>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContacts(contacts: List<LocalContactEntity>)

    @Query("SELECT * FROM local_contacts ORDER BY displayName ASC")
    suspend fun getAllContactsSync(): List<LocalContactEntity>

    @Query("DELETE FROM local_contacts WHERE phoneNumber = :number")
    suspend fun deleteByNumber(number: String)

    @Query("DELETE FROM local_contacts")
    suspend fun deleteAll()
}
