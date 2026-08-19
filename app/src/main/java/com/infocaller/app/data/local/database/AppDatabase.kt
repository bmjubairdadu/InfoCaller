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
import com.infocaller.app.data.local.dao.ContactBackupDao
import com.infocaller.app.data.local.entity.BlocklistEntity
import com.infocaller.app.data.local.entity.CallerEntity
import com.infocaller.app.data.local.entity.LocalContactEntity
import com.infocaller.app.data.local.entity.ContactBackupEntity

@Database(
    entities = [
        CallerEntity::class, 
        BlocklistEntity::class, 
        LocalContactEntity::class, 
        com.infocaller.app.data.local.entity.ContactEnrichmentEntity::class,
        com.infocaller.app.data.local.entity.EnrichmentQueueEntity::class,
        com.infocaller.app.data.local.entity.OperatorLogoEntity::class,
        ContactBackupEntity::class
    ], 
    version = 13, 
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callerDao(): CallerDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun localContactDao(): LocalContactDao
    abstract fun enrichmentDao(): com.infocaller.app.data.local.dao.EnrichmentDao
    abstract fun queueDao(): com.infocaller.app.data.local.dao.EnrichmentQueueDao
    abstract fun backupDao(): ContactBackupDao
    abstract fun operatorLogoDao(): com.infocaller.app.data.local.dao.OperatorLogoDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "infocaller_database"
                )
                .addMigrations(MIGRATION_12_13)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
