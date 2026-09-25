import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val releaseStorePassword = localProperties.getProperty("release.store.password") ?: ""
val releaseKeyAlias = localProperties.getProperty("release.key.alias") ?: ""
val releaseKeyPassword = localProperties.getProperty("release.key.password") ?: ""
val releaseStoreFilePath = localProperties.getProperty("release.store.file")
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
val brandfetchClientId = localProperties.getProperty("brandfetch.client.id") ?: ""
val supabaseUrl = localProperties.getProperty("supabase.url") ?: ""
val supabaseAnonKey = localProperties.getProperty("supabase.anon.key") ?: ""
val backendBaseUrl = localProperties.getProperty("backend.base.url") ?: ""
val infocallerApiKey = localProperties.getProperty("infocaller.api.key") ?: ""
val truecallerRequestSecret = localProperties.getProperty("truecaller.request.secret") ?: ""
val appSignatureSha256 = localProperties.getProperty("app.signature.sha256") ?: ""
val pinnedHosts = localProperties.getProperty("backend.pin.hosts") ?: ""
val certPins = localProperties.getProperty("backend.cert.pins") ?: ""

android {
    namespace = "com.infocaller.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.infocaller.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        androidResources {
            localeFilters += listOf("en", "bn")
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFilePath != null && releaseStoreFilePath.exists()) {
                storeFile = releaseStoreFilePath
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "BRANDFETCH_CLIENT_ID", "\"$brandfetchClientId\"")
            buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
            buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
            buildConfigField("String", "INFOCALLER_API_KEY", "\"$infocallerApiKey\"")
            buildConfigField("String", "TRUECALLER_REQUEST_SECRET", "\"$truecallerRequestSecret\"")
            buildConfigField("String", "APP_SIGNATURE_SHA256", "\"$appSignatureSha256\"")
            buildConfigField("String", "PINNED_HOSTS", "\"$pinnedHosts\"")
            buildConfigField("String", "CERT_PINS", "\"$certPins\"")
        }
        create("protected") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("release")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ""
            buildConfigField("String", "BRANDFETCH_CLIENT_ID", "\"$brandfetchClientId\"")
            buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
            buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
            buildConfigField("String", "INFOCALLER_API_KEY", "\"$infocallerApiKey\"")
            buildConfigField("String", "TRUECALLER_REQUEST_SECRET", "\"$truecallerRequestSecret\"")
            buildConfigField("String", "APP_SIGNATURE_SHA256", "\"$appSignatureSha256\"")
            buildConfigField("String", "PINNED_HOSTS", "\"$pinnedHosts\"")
            buildConfigField("String", "CERT_PINS", "\"$certPins\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "BRANDFETCH_CLIENT_ID", "\"\"")
            buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
            buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
            buildConfigField("String", "INFOCALLER_API_KEY", "\"$infocallerApiKey\"")
            buildConfigField("String", "TRUECALLER_REQUEST_SECRET", "\"$truecallerRequestSecret\"")
            buildConfigField("String", "APP_SIGNATURE_SHA256", "\"$appSignatureSha256\"")
            buildConfigField("String", "PINNED_HOSTS", "\"$pinnedHosts\"")
            buildConfigField("String", "CERT_PINS", "\"$certPins\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.libphonenumber)
    implementation("com.googlecode.libphonenumber:geocoder:2.248")
    implementation("com.googlecode.libphonenumber:carrier:1.238")
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.auth.api.phone)
}
