package com.infocaller.app

import android.app.Application
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.repository.*
import com.infocaller.app.domain.repository.*
import com.infocaller.app.domain.engine.*
import com.infocaller.app.data.remote.*
import kotlinx.coroutines.*

class InfoCallerApplication : Application() {
    lateinit var repository: CallerRepositoryImpl
    lateinit var deviceDataRepository: DeviceDataRepository
    lateinit var authRepository: AuthRepository
    lateinit var database: AppDatabase
    lateinit var lookupEngine: IPublicLookupEngine
    lateinit var imageAnalysisService: IImageAnalysisService
    lateinit var orchestrator: IScanOrchestrator
    lateinit var enrichmentEngine: ContinuousEnrichmentEngine
    lateinit var providerManager: ProviderManager
    lateinit var keyManager: ProviderKeyManager
    lateinit var registryService: RegistryApiService
    lateinit var operatorLogoManager: com.infocaller.app.util.OperatorLogoManager
    lateinit var truecallerAuthManager: TruecallerAuthManager
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        
        database = AppDatabase.getDatabase(this)
        deviceDataRepository = DeviceDataRepositoryImpl(contentResolver)
        authRepository = AuthRepositoryImpl()
        
        providerManager = ProviderManager(this)
        keyManager = ProviderKeyManager(this)
        operatorLogoManager = com.infocaller.app.util.OperatorLogoManager(this, database)

        // Optional backends removed - only Apify direct + free OSINT remain. Keep stub for relay fallback if user later adds backend.
        val currentBackendUrl = "https://api.infocaller.app/"
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl(currentBackendUrl)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()

        val backendService: BackendApiService? = try { retrofit.create(BackendApiService::class.java) } catch(_:Exception){ null }
        registryService = try { retrofit.create(RegistryApiService::class.java) } catch(_:Exception){ retrofit.create(RegistryApiService::class.java) }
        truecallerAuthManager = TruecallerAuthManager(this, backendService)
        
        val commonHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val sharedGson = com.google.gson.Gson()
        providerManager.registerProviders(listOf(
            LocalEnrichmentProvider(database.enrichmentDao()),
            RegistryLookupProvider(registryService),
            LocalRegionalMetadataProvider(),
            PhoneMetadataProviderImpl(this),
            GoogleSearchProviderImpl(),
            DorkingProviderImpl(),
            SocialEnumProviderImpl(),
            InstagramProviderImpl(this),
            UsernameLookupProviderImpl(commonHttpClient),
            TruecallerProviderImpl(this),
            WhatsappApifyProvider(this, backendService),
            ApifyLookupProviderImpl(backendService),
            LeakLookupProviderImpl(),
            TelegramLookupProvider(),
            EyeconProviderImpl(this),
            UpiLookupProviderImpl(),
            DisposablePhoneProviderImpl(),
            DarkWebLookupProviderImpl(),
            NominatimGeocodingProviderImpl(commonHttpClient, sharedGson),
            NidDatabaseProvider(database),
            NidGovEnrichmentProvider(database, commonHttpClient),
            NidIdentityLookupProviderImpl(),
            EmailLookupProviderImpl(commonHttpClient, sharedGson),
            HoleheEmailProviderImpl(commonHttpClient),
            WhatsMyNameProviderImpl(commonHttpClient, sharedGson),
            PhoneInfogaProviderImpl(),
            // Free search + OSINT expanders (no keys, no captcha)
            DuckDuckGoSearchProviderImpl(),
            BingSearchProviderImpl(),
            GitHubSearchProviderImpl(commonHttpClient),
            SherlockProviderImpl(commonHttpClient),
            FreePhoneValidationProviderImpl(commonHttpClient),
            CallerIdDeepOsintProvider(commonHttpClient),
            EmailSocialBridgeProvider(commonHttpClient),
            UsernameSocialDeepProvider(commonHttpClient),
            PhoneSocialBridgeProvider(commonHttpClient),
            NameSocialVerifierProvider(),
            ImageSocialVerifierProvider(),
            // Rich profile extractors (Osintgram / facebook-scraper / tiktok-scraper patterns - public web, no login)
            FacebookProfileProvider(),
            InstagramDeepProvider(this),
            TikTokProfileProvider()
        ))

        lookupEngine = PublicLookupEngine(providerManager)
        imageAnalysisService = ImageAnalysisService(this)
        // Import 115k NID database into Room on first launch (non-blocking)
        applicationScope.launch(Dispatchers.IO) {
            com.infocaller.app.data.local.NidDatabaseImporter.importIfNeeded(this@InfoCallerApplication, database)
        }
        val scanOrch = ScanOrchestrator(lookupEngine, imageAnalysisService, database.scanJobDao(), null, applicationScope)
        orchestrator = scanOrch

        repository = CallerRepositoryImpl(
            database.callerDao(),
            database.blocklistDao(),
            database.enrichmentDao(),
            lookupEngine,
            orchestrator,
            com.infocaller.app.util.AndroidContextResolver(this)
        )
        
        scanOrch.setResultSaver { result ->
            repository.saveLookupResult(result)
        }
        
        val enrichmentService = ContactEnrichmentService(this, lookupEngine, repository, database)
        
        enrichmentEngine = ContinuousEnrichmentEngine(
            this,
            database.queueDao(),
            database.enrichmentDao(),
            lookupEngine,
            orchestrator,
            repository,
            enrichmentService,
            backendService
        )

        com.infocaller.app.service.ScanningService.start(this)
    }
}
