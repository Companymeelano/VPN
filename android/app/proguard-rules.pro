# Meelano VPN — R8 rules. Only what the app actually needs, in the order the crashes happen.

# ---- keep the feed models: they are parsed by field name from JSON, and R8 renaming them is how
# ---- "the free list is empty but the file is 300 KB" gets reported as a bug three days later
-keepclassmembers class ir.meelano.vpn.data.FeedNode { *; }
-keepclassmembers class ir.meelano.vpn.data.FeedPayload { *; }
-keepclassmembers class ir.meelano.vpn.update.UpdateInfo { *; }
-keepclassmembers class ir.meelano.vpn.data.TunnelSpec { *; }

# ---- the service/tile/receiver are instantiated by name from the manifest; the plugin keeps them,
# ---- but the companion StateFlows are read reflectively from the tile process, so keep those fields
-keepclassmembers class ir.meelano.vpn.vpn.MeelanoVpnService$Companion {
    public static ** phase;
    public static ** traffic;
    public static *;
}

# ---- crypto / hashing for the update signature: a stripped Mac/MessageDigest path is a "signature
# ---- invalid" report that only reproduces on release builds, which is the worst kind of bug
-keep class javax.crypto.** { *; }
-keepclassmembers class java.security.** { *; }
-dontwarn javax.crypto.**

# ---- OkHttp on API 24 without a newer provider: keep the conscrypt-friendly path
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Kotlin coroutines debug metadata is 40 KB and useless in a release
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.debug.**

# ---- line numbers only; no source file names in a public APK
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute Meelano

# ---- Compose: R8 must not inline the recomposition lambda, or skipping breaks and the UI stutters
-dontwarn androidx.compose.**
-keep,includedescriptorclasses class androidx.compose.** { *; }
