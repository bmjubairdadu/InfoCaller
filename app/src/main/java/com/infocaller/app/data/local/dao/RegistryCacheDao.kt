package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.RegistryCacheEntity

@Dao
interface RegistryCacheDao {
    @Query("SELECT * FROM registry_cache WHERE normalizedPhoneNumber = :number LIMIT 1")
    suspend fun get(number: String): RegistryCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RegistryCacheEntity)

    @Query("DELETE FROM registry_cache WHERE normalizedPhoneNumber = :number")
    suspend fun delete(number: String)

    @Query("DELETE FROM registry_cache WHERE staleUntil < :now")
    suspend fun deleteExpired(now: Long)
}
