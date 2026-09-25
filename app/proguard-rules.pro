-allowaccessmodification
-repackageclasses 'com.infocaller.app.internal'
-overloadaggressively
-optimizationpasses 5

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

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
