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
            // RegistryLookup REMOVED: it called a relative URL
            // (api/v1/registry/lookup/...) against a raw.githubusercontent.com
            // Retrofit base — no such endpoint exists, so every scan burned an
            // attempt on an instant failure. SupabaseCommunityProvider is the
            // live read-only community path.
            SupabaseCommunityProvider(this, commonHttpClient),
            OfflineOperatorTablesProviderImpl(),
            CommunitySpamCsvProviderImpl(commonHttpClient),
            LocalRegionalMetadataProvider(),
            PhoneMetadataProviderImpl(this),
            // NOTE (live-test prune for BD mobiles): Google/Bing/DDG/Alt/Dorking/PhoneBridge
            // scrapers either CAPTCHA or hallucinate names from titles + filler About text;
            // PhoneInfoga always returns dork-link filler and its OVH branch is FR/BE/GB/ES/CH-only;
            // UPI is +91-gated; Instagram needs a login session; Telegram t.me heuristic
            // false-CONFIRMs (SocialEnum covers WA/TG presence); NidIdentity scrapes Google;
            // UsernameLookup/SocialDeep are strict subsets of Sherlock. All unregistered below.
            SocialEnumProviderImpl(),
            TruecallerProviderImpl(this),
            // LeakLookup (low-precision DDG leak hint) + Telegram (t.me false-CONFIRM;
            // SocialEnum covers WA/TG presence) + UPI (+91-gated, never hits +880) unregistered.
            EyeconProviderImpl(this),
            DisposablePhoneProviderImpl(),
            NominatimGeocodingProviderImpl(commonHttpClient, sharedGson),
            NidDatabaseProvider(database),
            NidGovEnrichmentProvider(database),
            // NidIdentityLookup (Google-scrape hallucination) unregistered — see note above.
            EmailLookupProviderImpl(commonHttpClient, sharedGson),
            HoleheEmailProviderImpl(commonHttpClient),
            WhatsMyNameProviderImpl(commonHttpClient, sharedGson),
            // PhoneInfoga (filler) unregistered — see note above.
            GitHubSearchProviderImpl(commonHttpClient),
            SherlockProviderImpl(commonHttpClient),
            // FreePhoneValidation (DDG-scrape filler dup of spam scraper) unregistered.
            CallerIdDeepOsintProvider(commonHttpClient),
            GrepAppCodeSearchProviderImpl(commonHttpClient),
            SpamReputationScraperProviderImpl(),
            XposedOrNotBreachProviderImpl(commonHttpClient),
            // AltSearchScraper (hallucinated names/filler) unregistered — see note above.
            DisifyEmailValidationProviderImpl(commonHttpClient),
            HackerTargetDomainReconProviderImpl(commonHttpClient),
            EmailSocialBridgeProvider(commonHttpClient),
            // UsernameSocialDeep (Sherlock subset) + PhoneSocialBridge (dup) unregistered.
            NameSocialVerifierProvider(),
            ImageSocialVerifierProvider(),
            FacebookProfileProvider(),
            InstagramDeepProvider(this),
            TikTokProfileProvider(),
            // Deep-scan kit (all free, keyless): Maigret 120-site username
            // sweep, HudsonRock-style email breach+paste intel, Sync.ME phone
            // pivot, multi-avatar harvester, reverse-image links, face-search
            // pivot, AI-assist deep queries. Registered AFTER the core set so
            // the 8-attempt budget still spends on Truecaller/Eyecon/NID
            // first; these fire on attempts 5-8 and on EMAIL/USERNAME scans.
            MaigretSweepProviderImpl(commonHttpClient),
            HudsonRockEmailIntelProviderImpl(commonHttpClient),
            SocialSearcherPhoneProviderImpl(commonHttpClient),
            MultiAvatarHarvesterProviderImpl(commonHttpClient),
            ReverseImageSearchProviderImpl(commonHttpClient),
            PimeyesPhotoPivotProviderImpl(commonHttpClient),
            AiAssistDeepSearchProviderImpl(commonHttpClient)
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
