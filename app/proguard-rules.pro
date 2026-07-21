# 流御守护 ProGuard混淆规则

# ============================================================================
# 基本规则
# ============================================================================

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================================
# Kotlin
# ============================================================================

-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# ============================================================================
# Coroutines
# ============================================================================

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ============================================================================
# Compose
# ============================================================================

-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ============================================================================
# Serialization
# ============================================================================

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class com.liuyuguard.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.liuyuguard.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all model classes for serialization
-keep class com.liuyuguard.model.** { *; }

# ============================================================================
# Shizuku
# ============================================================================

-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ============================================================================
# libsu (Root Shell)
# ============================================================================

-keep class com.topjohnwu.libsu.** { *; }
-dontwarn com.topjohnwu.libsu.**

# ============================================================================
# Vico Charts
# ============================================================================

-keep class com.patrykandpatrick.vico.** { *; }
-dontwarn com.patrykandpatrick.vico.**

# ============================================================================
# MPAndroidChart
# ============================================================================

-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ============================================================================
# Application & Service (不能被混淆)
# ============================================================================

-keep class com.liuyuguard.base.LiuYuApplication { *; }
-keep class com.liuyuguard.service.MainTrafficService { *; }
-keep class com.liuyuguard.service.GuardDaemonService { *; }
-keep class com.liuyuguard.receiver.BootCompletedReceiver { *; }
-keep class com.liuyuguard.ui.MainActivity { *; }

# ============================================================================
# 枚举
# ============================================================================

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# DataStore Preferences
# ============================================================================

-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**