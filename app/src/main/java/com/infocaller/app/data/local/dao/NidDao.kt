package com.infocaller.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.infocaller.app.data.local.entity.NidEntity

@Dao
interface NidDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<NidEntity>)

    @Query("SELECT * FROM nid_records WHERE REPLACE(REPLACE(number,'+',''), ' ','') LIKE '%' || :digits || '%' LIMIT 1")
    suspend fun findByPhone(digits: String): NidEntity?

    @Query("SELECT * FROM nid_records WHERE nid = :nid LIMIT 1")
    suspend fun findByNid(nid: String): NidEntity?

    @Query("SELECT * FROM nid_records WHERE nid = :nid AND dob = :dob LIMIT 1")
    suspend fun findByNidAndDob(nid: String, dob: String): NidEntity?

    @Query("SELECT * FROM nid_records WHERE dob = :dob LIMIT 20")
    suspend fun findByDob(dob: String): List<NidEntity>

    @Query("SELECT * FROM nid_records WHERE nid LIKE :query OR number LIKE :query LIMIT 50")
    suspend fun search(query: String): List<NidEntity>

    @Query("SELECT COUNT(*) FROM nid_records")
    suspend fun count(): Int
}
