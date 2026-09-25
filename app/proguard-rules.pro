-allowaccessmodification
-overloadaggressively
-optimizationpasses 7

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

-assumenosideeffects class java.lang.Throwable {
    public <init>(...);
    public void printStackTrace(...);
}

-repackageclasses 'com.infocaller.app.internal'

-repackageclasses 'com.infocaller.app.internal'
-keeppackagenames
-dontusemixedcaseclassnames
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Gson needs these to reflect over the model classes
-keep class com.infocaller.app.domain.model.** { *; }
-keep class com.infocaller.app.data.local.entity.** { *; }
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.android.HandlerContext {
    public <fields>;
}

-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

-keepnames class com.infocaller.app.domain.model.**
-keepnames class com.infocaller.app.data.local.entity.**

-keep class * extends androidx.room.RoomDatabase
-keep class androidx.room.util.TableInfo { *; }
-keep class androidx.room.util.TableInfo$Column { *; }
-keep class androidx.room.util.TableInfo$ForeignKey { *; }
-keep class androidx.room.util.TableInfo$Index { *; }
-keep @androidx.room.Entity class *
-keep interface * extends androidx.room.RoomDatabase
-keep class * implements androidx.room.RoomDatabase

-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

-keep class org.jsoup.** { *; }

-keep class coil.** { *; }

-keep class com.infocaller.app.domain.model.** { *; }
-keep class com.infocaller.app.data.local.entity.** { *; }
-keep class com.infocaller.app.data.remote.dto.** { *; }

# Security checks must not be renamed away or stripped
-keep class com.infocaller.app.security.** { *; }
-keep class com.infocaller.app.BuildConfig { *; }

# OkHttp and Okio ship with references that are not all present
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

