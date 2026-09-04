package com.infocaller.app.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.infocaller.app.data.local.dao.CallerDao
import com.infocaller.app.data.local.dao.BlocklistDao
import com.infocaller.app.data.local.dao.LocalContactDao
import com.infocaller.app.data.local.entity.BlocklistEntity
import com.infocaller.app.data.local.entity.CallerEntity
import com.infocaller.app.data.local.entity.LocalContactEntity

@Database(
    entities = [
        CallerEntity::class, 
        BlocklistEntity::class, 
        LocalContactEntity::class, 
        com.infocaller.app.data.local.entity.ContactEnrichmentEntity::class,
        com.infocaller.app.data.local.entity.EnrichmentQueueEntity::class,
        com.infocaller.app.data.local.entity.OperatorLogoEntity::class,
        com.infocaller.app.data.local.entity.ScanJobStateEntity::class,
        com.infocaller.app.data.local.entity.NidEntity::class,
        com.infocaller.app.data.local.entity.ContributionEntity::class
    ], 
    version = 23, 
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callerDao(): CallerDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun localContactDao(): LocalContactDao
    abstract fun enrichmentDao(): com.infocaller.app.data.local.dao.EnrichmentDao
    abstract fun queueDao(): com.infocaller.app.data.local.dao.EnrichmentQueueDao
    abstract fun contributionDao(): com.infocaller.app.data.local.dao.ContributionDao
    abstract fun operatorLogoDao(): com.infocaller.app.data.local.dao.OperatorLogoDao
    abstract fun scanJobDao(): com.infocaller.app.data.local.dao.ScanJobDao
    abstract fun nidDao(): com.infocaller.app.data.local.dao.NidDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN alternateName TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN timezone TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN email TEXT")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN lineType TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN plateNumber TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN plateNumberSource TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN iban TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN ibanSource TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN vatId TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN vatIdSource TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN macAddress TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN macAddressSource TEXT")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN photoCandidatesJson TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN alternateNamesJson TEXT")
                database.execSQL("ALTER TABLE contact_enrichment ADD COLUMN lastScannedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE callers ADD COLUMN localName TEXT")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS scan_job_states (phoneNumber TEXT NOT NULL, completedProviders TEXT NOT NULL, satisfiedCapabilities TEXT NOT NULL, lastUpdated INTEGER NOT NULL, PRIMARY KEY(phoneNumber))")
            }
        }
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS nid_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, number TEXT NOT NULL, nid TEXT NOT NULL, dob TEXT NOT NULL, `database` TEXT, tg TEXT, nameEn TEXT, nameBn TEXT, fatherName TEXT, motherName TEXT, address TEXT, photoUrl TEXT, photoBase64 TEXT, lastEnrichedAt INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_nid_records_nid ON nid_records(nid)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_nid_records_number ON nid_records(number)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_nid_records_dob ON nid_records(dob)")
            }
        }
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS contribution_queue (phoneHash TEXT NOT NULL, displayName TEXT, payloadFingerprint TEXT NOT NULL, status TEXT NOT NULL, attemptCount INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, lastAttemptAt INTEGER NOT NULL, lastError TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(phoneHash))")
            }
        }
        // v22: query indices only — no column changes. IF NOT EXISTS keeps this
        // idempotent for installs that already created indices via entities.
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_contact_enrichment_contactId ON contact_enrichment(contactId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_contact_enrichment_expiresAt ON contact_enrichment(expiresAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_local_contacts_phoneNumber ON local_contacts(phoneNumber)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_local_contacts_isSynced ON local_contacts(isSynced)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_enrichment_queue_status_nextAttemptAt ON enrichment_queue(status, nextAttemptAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_contribution_queue_status_nextAttemptAt ON contribution_queue(status, nextAttemptAt)")
            }
        }
        // v23: drop the never-used contact_backups table (zero readers/writers).
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS contact_backups")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "infocaller_database"
                )
                .addMigrations(MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
