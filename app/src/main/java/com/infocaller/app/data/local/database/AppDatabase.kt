package com.infocaller.app.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        com.infocaller.app.data.local.entity.EnrichmentQueueEntity::class
    ], 
    version = 6, 
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callerDao(): CallerDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun localContactDao(): LocalContactDao
    abstract fun enrichmentDao(): com.infocaller.app.data.local.dao.EnrichmentDao
    abstract fun queueDao(): com.infocaller.app.data.local.dao.EnrichmentQueueDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "infocaller_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
