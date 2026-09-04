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
    lateinit var registryService: RegistryApiService
    lateinit var operatorLogoManager: com.infocaller.app.util.OperatorLogoManager
    lateinit var truecallerAuthManager: TruecallerAuthManager
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // A corrupt DB file throws inside Room.databaseBuilder and would kill every
        // launch ("keeps stopping" loop). All tables are re-syncable caches, so on
        // failure delete once and rebuild instead of boot-looping.
        database = try {
            AppDatabase.getDatabase(this)
        } catch (_: Exception) {
            try {
                deleteDatabase("infocaller_database")
            } catch (_: Exception) { }
            AppDatabase.getDatabase(this)
        }
        deviceDataRepository = DeviceDataRepositoryImpl(contentResolver)
        authRepository = AuthRepositoryImpl()

        providerManager = ProviderManager(this)
        operatorLogoManager = com.infocaller.app.util.OperatorLogoManager(this, database)

        // Registry manifest is fetched with @Url — base URL must end with '/'.
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl("https://raw.githubusercontent.com/")
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()

        registryService = retrofit.create(RegistryApiService::class.java)
        truecallerAuthManager = TruecallerAuthManager(this)
        
        val commonHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val sharedGson = com.google.gson.Gson()
        providerManager.registerProviders(listOf(
            LocalEnrichmentProvider(database.enrichmentDao()),
            OwnerVerifiedLookupProvider(this, commonHttpClient),
            RegistryLookupProvider(registryService),
            SupabaseCommunityProvider(this, commonHttpClient),
            OfflineOperatorTablesProviderImpl(),
            CommunitySpamCsvProviderImpl(commonHttpClient),
            LocalRegionalMetadataProvider(),
            PhoneMetadataProviderImpl(this),
            GoogleSearchProviderImpl(),
            DorkingProviderImpl(),
            SocialEnumProviderImpl(),
            InstagramProviderImpl(this),
            UsernameLookupProviderImpl(commonHttpClient),
            TruecallerProviderImpl(this),
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
            DuckDuckGoSearchProviderImpl(),
            BingSearchProviderImpl(),
            GitHubSearchProviderImpl(commonHttpClient),
            SherlockProviderImpl(commonHttpClient),
            FreePhoneValidationProviderImpl(commonHttpClient),
            CallerIdDeepOsintProvider(commonHttpClient),
            GrepAppCodeSearchProviderImpl(commonHttpClient),
            SpamReputationScraperProviderImpl(),
            XposedOrNotBreachProviderImpl(commonHttpClient),
            AlternativeSearchScraperProviderImpl(),
            DisifyEmailValidationProviderImpl(commonHttpClient),
            HackerTargetDomainReconProviderImpl(commonHttpClient),
            EmailSocialBridgeProvider(commonHttpClient),
            UsernameSocialDeepProvider(commonHttpClient),
            PhoneSocialBridgeProvider(commonHttpClient),
            NameSocialVerifierProvider(),
            ImageSocialVerifierProvider(),
            FacebookProfileProvider(),
            InstagramDeepProvider(this),
            TikTokProfileProvider()
        ))

        lookupEngine = PublicLookupEngine(providerManager)
        imageAnalysisService = ImageAnalysisService(this)
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
            enrichmentService
        )

        // Never let a background-process start kill the app: starting an FGS from
        // background throws on Android 12+ (ForegroundServiceStartNotAllowedException).
        // If this throw escaped, every lateinit above would stay uninitialized and the
        // next Activity access would crash with UninitializedPropertyAccessException.
        try {
            com.infocaller.app.service.ScanningService.start(this)
        } catch (_: Exception) { }
    }
}
