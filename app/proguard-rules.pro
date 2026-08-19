# General Android/Kotlin rules
-keepattributes *Annotation*, Signature, EnclosingMethod, InnerClasses, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations

# Retrofit
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses

# GSON
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# PROTECT YOUR API INTERFACE
# This is critical for Retrofit suspend functions
-keep interface com.hidong.gongbus.RunningApi {
    <methods>;
}

# PROTECT YOUR DATA MODELS
# Using -keep,allowobfuscation is sometimes safer but here we want to keep them fully intact
-keep class com.hidong.gongbus.ActivityFeedItem { *; }
-keep class com.hidong.gongbus.ActivityDetail { *; }
-keep class com.hidong.gongbus.MetricRecord { *; }
-keep class com.hidong.gongbus.Comment { *; }
-keep class com.hidong.gongbus.UserProfile { *; }
-keep class com.hidong.gongbus.DashboardData { *; }
-keep class com.hidong.gongbus.WeeklyMileage { *; }
-keep class com.hidong.gongbus.LeaderboardEntry { *; }
-keep class com.hidong.gongbus.AvatarResponse { *; }
-keep class com.hidong.gongbus.CommentPayload { *; }
-keep class com.hidong.gongbus.LikePayload { *; }

# Kotlin Coroutines and Metadata
# Retrofit relies on these for suspend functions
-keep class kotlin.coroutines.Continuation { *; }
-keep class kotlin.Metadata { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.reflect.**

# Google Maps
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }
