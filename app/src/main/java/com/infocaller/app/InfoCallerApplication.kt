package com.infocaller.app

import android.app.Application
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.remote.CallerScraper
import com.infocaller.app.data.repository.AuthRepositoryImpl
import com.infocaller.app.data.repository.CallerRepositoryImpl
import com.infocaller.app.data.repository.DeviceDataRepositoryImpl
import com.infocaller.app.domain.repository.AuthRepository
import com.infocaller.app.domain.repository.DeviceDataRepository

class InfoCallerApplication : Application() {
    lateinit var repository: CallerRepositoryImpl
    lateinit var deviceDataRepository: DeviceDataRepository
    lateinit var authRepository: AuthRepository
    lateinit var database: AppDatabase
    lateinit var lookupEngine: com.infocaller.app.domain.engine.PublicLookupEngine
    lateinit var enrichmentEngine: com.infocaller.app.domain.engine.ContinuousEnrichmentEngine
    lateinit var providerManager: com.infocaller.app.domain.engine.ProviderManager
    lateinit var registryService: com.infocaller.app.data.remote.RegistryApiService

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        val callerScraper = CallerScraper(this)
        repository = CallerRepositoryImpl(
            database.callerDao(),
            database.blocklistDao(),
            callerScraper,
            this
        )
        // CRITICAL: Initialize CallOverlayService repository
        com.infocaller.app.service.CallOverlayService.setRepository(repository)
        
        deviceDataRepository = DeviceDataRepositoryImpl(contentResolver)
        authRepository = AuthRepositoryImpl()

        // 1. Initialize Provider Manager
        providerManager = com.infocaller.app.domain.engine.ProviderManager(this)

        // 2. Initialize Retrofit for Backend Relay (Development Mode: Configurable)
        val currentBackendUrl = providerManager.backendUrl.value.ifBlank { "https://localhost/" }
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl(currentBackendUrl)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
        val backendService = retrofit.create(com.infocaller.app.data.remote.BackendApiService::class.java)
        registryService = retrofit.create(com.infocaller.app.data.remote.RegistryApiService::class.java)

        // 3. Register Providers
        providerManager.registerProvider(com.infocaller.app.data.remote.PhoneMetadataProviderImpl(this))
        providerManager.registerProvider(com.infocaller.app.data.remote.SpamProviderImpl(database.callerDao()))
        providerManager.registerProvider(com.infocaller.app.data.remote.SocialLookupProviderImpl())
        providerManager.registerProvider(com.infocaller.app.data.remote.GoogleSearchProviderImpl())
        providerManager.registerProvider(com.infocaller.app.data.remote.BusinessProviderImpl())
        providerManager.registerProvider(com.infocaller.app.data.remote.TruecallerProviderImpl(this))
        providerManager.registerProvider(com.infocaller.app.data.remote.ApifyLookupProviderImpl(this, backendService))

        // 4. Initialize Lookup Engine
        lookupEngine = com.infocaller.app.domain.engine.PublicLookupEngine(providerManager)

        // 5. Initialize Continuous Enrichment Engine
        enrichmentEngine = com.infocaller.app.domain.engine.ContinuousEnrichmentEngine(
            this,
            database.queueDao(),
            database.enrichmentDao(),
            lookupEngine
        )
    }
}
