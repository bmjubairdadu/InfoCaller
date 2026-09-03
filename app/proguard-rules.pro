# ProGuard rules for InfoCaller

# Aggressive Obfuscation & Hardening
-allowaccessmodification
-repackageclasses 'com.infocaller.app.internal'
-overloadaggressively
-optimizationpasses 5

# Remove Debug Logging in Release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# Preserve necessary attributes
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.android.HandlerContext {
    public <fields>;
}

# Room & Models
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# Prevent reflection on internal classes
-keepnames class com.infocaller.app.domain.model.**
-keepnames class com.infocaller.app.data.local.entity.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class androidx.room.util.TableInfo { *; }
-keep class androidx.room.util.TableInfo$Column { *; }
-keep class androidx.room.util.TableInfo$ForeignKey { *; }
-keep class androidx.room.util.TableInfo$Index { *; }
-keep @androidx.room.Entity class *
-keep interface * extends androidx.room.RoomDatabase
-keep class * implements androidx.room.RoomDatabase

# Retrofit & OkHttp
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Jsoup
-keep class org.jsoup.** { *; }

# Coil
-keep class coil.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# App Specific Models
-keep class com.infocaller.app.domain.model.** { *; }
-keep class com.infocaller.app.data.local.entity.** { *; }
-keep class com.infocaller.app.data.remote.dto.** { *; }
