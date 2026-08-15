package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.CallerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallerDao {
    @Query("SELECT * FROM callers WHERE phoneNumber = :phoneNumber")
    fun getCaller(phoneNumber: String): Flow<CallerEntity?>

    @Query("SELECT * FROM callers WHERE phoneNumber = :phoneNumber")
    suspend fun getCallerSync(phoneNumber: String): CallerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCaller(caller: CallerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallers(callers: List<CallerEntity>)

    @Query("SELECT COUNT(*) > 0 FROM callers WHERE phoneNumber = :phoneNumber AND spamStatus = 'SPAM'")
    suspend fun isSpam(phoneNumber: String): Boolean

    @Delete
    suspend fun deleteCaller(caller: CallerEntity)
}
