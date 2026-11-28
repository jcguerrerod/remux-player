# Media3 / ExoPlayer Proguard Rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Native JNI entrypoints and native callbacks
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.player.coremedia.player.** { *; }
-keep class com.player.coremedia.audio.** { *; }
-keep class com.player.coremedia.video.** { *; }
-keep class com.player.coremedia.jellyfin.** { *; }
-keep class com.player.coremedia.security.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor CIO & Coroutines
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# Ktor / SLF4J optional bindings
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.**
