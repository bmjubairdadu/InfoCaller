package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.ScanJobStateEntity

@Dao
interface ScanJobDao {
    @Query("SELECT * FROM scan_job_states WHERE phoneNumber = :number")
    suspend fun getState(number: String): ScanJobStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: ScanJobStateEntity)

    @Query("DELETE FROM scan_job_states WHERE phoneNumber = :number")
    suspend fun deleteState(number: String)
}
