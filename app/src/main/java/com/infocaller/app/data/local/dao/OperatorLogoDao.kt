package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.OperatorLogoEntity

@Dao
interface OperatorLogoDao {
    @Query("SELECT * FROM operator_logos WHERE operatorKey = :key")
    suspend fun getLogo(key: String): OperatorLogoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogo(logo: OperatorLogoEntity)

    @Query("SELECT * FROM operator_logos")
    suspend fun getAllLogos(): List<OperatorLogoEntity>
}
