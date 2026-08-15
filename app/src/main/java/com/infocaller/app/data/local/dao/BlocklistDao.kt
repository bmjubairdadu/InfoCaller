package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.BlocklistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlocklistDao {
    @Query("SELECT * FROM blocklist ORDER BY addedAt DESC")
    fun getAllBlocked(): Flow<List<BlocklistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(entity: BlocklistEntity)

    @Query("DELETE FROM blocklist WHERE phoneNumber = :phoneNumber")
    suspend fun unblock(phoneNumber: String)

    @Query("SELECT COUNT(*) > 0 FROM blocklist WHERE phoneNumber = :phoneNumber")
    suspend fun isBlocked(phoneNumber: String): Boolean
}
