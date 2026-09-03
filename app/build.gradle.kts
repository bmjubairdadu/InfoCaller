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
val apifyToken1 = localProperties.getProperty("apify.token.1") ?: ""
val apifyToken2 = localProperties.getProperty("apify.token.2") ?: ""
val tcClientSecret = localProperties.getProperty("truecaller.client.secret") ?: ""
val brandfetchClientId = localProperties.getProperty("brandfetch.client.id") ?: ""

android {
    namespace = "com.infocaller.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.infocaller.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        androidResources {
            localeFilters += listOf("en", "bn")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "APIFY_TOKEN_1", "\"$apifyToken1\"")
            buildConfigField("String", "APIFY_TOKEN_2", "\"$apifyToken2\"")
            buildConfigField("String", "TRUECALLER_CLIENT_SECRET", "\"$tcClientSecret\"")
            buildConfigField("String", "BRANDFETCH_CLIENT_ID", "\"$brandfetchClientId\"")
        }
        release {
            buildConfigField("String", "APIFY_TOKEN_1", "\"\"")
            buildConfigField("String", "APIFY_TOKEN_2", "\"\"")
            buildConfigField("String", "TRUECALLER_CLIENT_SECRET", "\"$tcClientSecret\"")
            buildConfigField("String", "BRANDFETCH_CLIENT_ID", "\"$brandfetchClientId\"")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.libphonenumber)
    implementation("com.googlecode.libphonenumber:geocoder:2.248")
    implementation("com.googlecode.libphonenumber:carrier:1.238")
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.runtime.livedata)
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")

    testImplementation(libs.junit)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
