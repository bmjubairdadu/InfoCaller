package com.infocaller.app.data.local.dao

import androidx.room.*
import com.infocaller.app.data.local.entity.ContactEnrichmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EnrichmentDao {
    @Query("SELECT * FROM contact_enrichment WHERE normalizedPhoneNumber = :number")
    fun getEnrichment(number: String): Flow<ContactEnrichmentEntity?>

    @Query("SELECT * FROM contact_enrichment WHERE normalizedPhoneNumber IN (:numbers)")
    fun getEnrichments(numbers: List<String>): Flow<List<ContactEnrichmentEntity>>

    @Query("SELECT * FROM contact_enrichment WHERE normalizedPhoneNumber = :number")
    suspend fun getEnrichmentSync(number: String): ContactEnrichmentEntity?

    @Query("SELECT * FROM contact_enrichment WHERE normalizedPhoneNumber IN (:numbers)")
    suspend fun getEnrichmentsSync(numbers: List<String>): List<ContactEnrichmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnrichment(enrichment: ContactEnrichmentEntity)
}
