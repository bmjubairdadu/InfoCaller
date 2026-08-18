package com.infocaller.app

import android.app.Application
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.repository.*
import com.infocaller.app.domain.repository.*
import com.infocaller.app.domain.engine.*
import com.infocaller.app.data.remote.*

class InfoCallerApplication : Application() {
    lateinit var repository: CallerRepositoryImpl
    lateinit var deviceDataRepository: DeviceDataRepository
    lateinit var authRepository: AuthRepository
    lateinit var database: AppDatabase
    lateinit var lookupEngine: PublicLookupEngine
    lateinit var enrichmentEngine: ContinuousEnrichmentEngine
    lateinit var providerManager: ProviderManager
    lateinit var registryService: RegistryApiService
    lateinit var operatorLogoManager: com.infocaller.app.util.OperatorLogoManager

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        
        deviceDataRepository = DeviceDataRepositoryImpl(contentResolver)
        authRepository = AuthRepositoryImpl()

        providerManager = ProviderManager(this)
        operatorLogoManager = com.infocaller.app.util.OperatorLogoManager(this, database)

        val currentBackendUrl = providerManager.backendUrl.value.ifBlank { "http://localhost:3000/" }
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl(currentBackendUrl)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
        
        val backendService = retrofit.create(BackendApiService::class.java)
        registryService = retrofit.create(RegistryApiService::class.java)

        // 3. Register Providers
        providerManager.registerProvider(RegistryLookupProvider(registryService)) // Shared Registry First
        providerManager.registerProvider(PhoneMetadataProviderImpl(this))
        providerManager.registerProvider(SpamProviderImpl(database.callerDao()))
        providerManager.registerProvider(GoogleSearchProviderImpl())
        providerManager.registerProvider(BusinessProviderImpl())
        providerManager.registerProvider(TruecallerProviderImpl(this))
        providerManager.registerProvider(ApifyLookupProviderImpl(backendService))

        lookupEngine = PublicLookupEngine(providerManager)

        repository = CallerRepositoryImpl(
            database.callerDao(),
            database.blocklistDao(),
            lookupEngine,
            this
        )
        
        com.infocaller.app.service.CallOverlayService.setRepository(repository)

        val enrichmentService = ContactEnrichmentService(this, lookupEngine, repository, database)
        
        enrichmentEngine = ContinuousEnrichmentEngine(
            this,
            database.queueDao(),
            database.enrichmentDao(),
            lookupEngine,
            enrichmentService,
            backendService
        )
        
        enrichmentEngine.startProcessing()
    }
}
