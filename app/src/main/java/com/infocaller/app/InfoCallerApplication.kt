package com.infocaller.app

import android.app.Application
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.repository.*
import com.infocaller.app.domain.repository.*
import com.infocaller.app.domain.engine.*
import com.infocaller.app.data.remote.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
    lateinit var operatorLogoManager: com.infocaller.app.util.OperatorLogoManager
    lateinit var truecallerAuthManager: TruecallerAuthManager
    lateinit var commonHttpClient: OkHttpClient
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
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

        commonHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        providerManager = ProviderManager(this)
        operatorLogoManager = com.infocaller.app.util.OperatorLogoManager(this, database)

        truecallerAuthManager = TruecallerAuthManager(this)

        val sharedGson = com.google.gson.Gson()
        providerManager.registerProviders(listOf(
            LocalEnrichmentProvider(database.enrichmentDao()),
            OwnerVerifiedLookupProvider(this, commonHttpClient),
            SupabaseCommunityProvider(this),
            OfflineOperatorTablesProviderImpl(),
            CommunitySpamCsvProviderImpl(commonHttpClient),
            LocalRegionalMetadataProvider(),
            PhoneMetadataProviderImpl(this),
            SocialEnumProviderImpl(),
            TruecallerProviderImpl(this),
            EyeconProviderImpl(this),
            DisposablePhoneProviderImpl(),
            NidDatabaseProvider(database),
            EmailLookupProviderImpl(commonHttpClient, sharedGson),
            HoleheEmailProviderImpl(commonHttpClient),
            WhatsMyNameProviderImpl(commonHttpClient, sharedGson),
            GitHubSearchProviderImpl(commonHttpClient),
            SherlockProviderImpl(commonHttpClient),
            CallerIdDeepOsintProvider(commonHttpClient),
            GrepAppCodeSearchProviderImpl(commonHttpClient),
            SpamReputationScraperProviderImpl(),
            XposedOrNotBreachProviderImpl(commonHttpClient),
            DisifyEmailValidationProviderImpl(commonHttpClient),
            EmailSocialBridgeProvider(commonHttpClient),
            FacebookProfileProvider(),
            InstagramDeepProvider(this),
            TikTokProfileProvider(),
            MaigretSweepProviderImpl(commonHttpClient),
            SocialSearcherPhoneProviderImpl(commonHttpClient),
            SocialAccountEnumeratorProviderImpl(commonHttpClient),
            NamePhotoSocialPivotProviderImpl(commonHttpClient),
            MultiAvatarHarvesterProviderImpl(commonHttpClient),
            ReverseImageSearchProviderImpl(commonHttpClient),
            FaceMatchedReverseSearchProviderImpl(),
            PimeyesPhotoPivotProviderImpl(commonHttpClient),
            AiAssistDeepSearchProviderImpl(commonHttpClient),
            LinkedInProfileProviderImpl(commonHttpClient),
            XProfileProviderImpl(commonHttpClient),
            YouTubeProfileProviderImpl(commonHttpClient),
            PinterestMediumProviderImpl(commonHttpClient),
            GamingProfileProviderImpl(commonHttpClient),
            TelegramDeepProviderImpl(commonHttpClient),
            MusicCreatorProviderImpl(commonHttpClient),
            PhoneSocialBridgeProviderImpl(commonHttpClient),
            EmailDeepSocialProviderImpl(commonHttpClient)
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

        try {
            com.infocaller.app.service.ScanningService.start(this)
        } catch (_: Exception) { }
    }
}
