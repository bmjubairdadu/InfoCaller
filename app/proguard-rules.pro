# ProGuard rules for InfoCaller

# Aggressive Obfuscation
-allowaccessmodification
-repackageclasses ''
-overloadaggressively

# Android and Kotlin
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

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
