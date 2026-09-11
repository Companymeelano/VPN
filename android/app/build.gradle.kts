import java.util.Properties

/**
 * The app module.
 *
 * The buildConfigFields below are the whole contract with the shared host, and they are the ONLY
 * place these values are written: a debug build pointed at a staging folder must be a build flag,
 * never an edit inside a Kotlin file (that is how a test URL ships to production).
 *
 *   MEELANO_FEED_BASE    -> https://ainetmee.ir/v
 *   MEELANO_FEED_KEY     -> X-Meelano-Key value for the VIP/feedback endpoints
 *   MEELANO_FEED_SECRET  -> HMAC key used to verify version.json (see below)
 *   MEELANO_SELF_UPDATE  -> "true" only for the release you actually distribute from the host
 *
 * Put the key/secret in `android/local.properties` (git-ignored), never in this file.
 *
 * `MEELANO_FEED_KEY` is the access key the app sends to gate the VIP file; `MEELANO_FEED_SECRET`
 * is the same HMAC key the server uses to sign version.json. Being honest about what that buys:
 * anything inside an APK is recoverable, so the key is an *access* control (keeps the list off
 * random scanners) and the signature is an *integrity* check on the update file. Neither is a
 * secret in the server-to-server sense, and the design never claims otherwise — the two things
 * that actually need to stay private (node credentials, the admin panel) never ship in the client.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val feedBase = providers.gradleProperty("MEELANO_FEED_BASE").orNull
    ?: "https://ainetmee.ir/v"
val feedKey = providers.gradleProperty("MEELANO_FEED_KEY").orNull
    ?: (rootProject.file("local.properties").let { if (it.isFile) Properties().apply { load(it.inputStream()) }.getProperty("MEELANO_FEED_KEY") else null })
    ?: ""
val feedSecret = providers.gradleProperty("MEELANO_FEED_SECRET").orNull
    ?: (rootProject.file("local.properties").let { if (it.isFile) Properties().apply { load(it.inputStream()) }.getProperty("MEELANO_FEED_SECRET") else null })
    ?: ""
val selfUpdate = providers.gradleProperty("MEELANO_SELF_UPDATE").orNull ?: "true"
// true ONLY when a real engine (tProxy / sing-box) is a dependency and CoreApi calls it.
val coreLinked = providers.gradleProperty("MEELANO_CORE_LINKED").orNull ?: "false"
if (coreLinked != "true" && gradle.startParameter.taskNames.any { it.contains("Release", true) }) {
    throw GradleException(
        "MEELANO_CORE_LINKED=false: این بیلد تونل نمی‌زند، پس ریلیز نساز. " +
        "اول وابستگی هسته را در همین فایل باز کن و CoreApi را وصل کن، بعد با -PMEELANO_CORE_LINKED=true بیلد بگیر. " +
        "debug برای تست UI/فید/آپدیت آزاد است."
    )
}
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) load(f.inputStream())
}

android {
    namespace = "ir.meelano.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.meelano.vpn"
        minSdk = 24
        targetSdk = 35
        // bump both together; versionName is what the update sheet prints, versionCode is what the
        // feed compares (see backend/v/lib/Version.php) and an APK with a code already "seen" is a
        // silent no-op for every user.
        //
        // scheme: major*100000 + minor*100 + patch  ->  2.1.0 = 201_000. It is arithmetic, not a date,
        // because a date code (20240911) overflows the int some tooling prints and it tells you nothing
        // about which build is newer when two are built on the same day.
        versionCode = 202_000
        versionName = "2.2.0"

        resourceConfigurations += listOf("fa", "en")
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "FEED_BASE_URL", "\"$feedBase\"")
        buildConfigField("String", "MEELANO_FEED_KEY", "\"$feedKey\"")
        buildConfigField("String", "MEELANO_FEED_SECRET", "\"$feedSecret\"")
        buildConfigField("boolean", "SELF_UPDATE", selfUpdate)
        buildConfigField("boolean", "CORE_LINKED", coreLinked)
        buildConfigField("String", "CHANNEL", "\"stable\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("meelano") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v2/v3 are on by default in AGP 8 and minSdk 24 means v1 is never required
                // (APK signature scheme v2 is supported from API 24), so nothing is configured here.
                // Setting enableV1Signing/enableV2Signing explicitly is a removed API in some AGP versions.
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            buildConfigField("String", "CHANNEL", "\"debug\"")
            // a debug build must never self-update against the production file: the installed debug
            // signature differs and the installer prompt would fail in a way users blame on the app
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
        release {
            // minify ON is not hygiene theatre here: the feed parser + HMAC verify classes must stay
            // findable by name for the proguard rules below to make sense, and it removes ~35% size
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("meelano")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "DebugProbesKt.bin",          // 40 KB of coroutine debug metadata nobody reads in production
        )
    }

    // NOTE: no `generateLocaleConfig` yet - per-app language needs a locales_config that matches the
    // feed's supported countries; the app is Persian-by-default (values/ = fa) until that is decided.

    lint {
        // lint is a review step, not a build gate: a custom-issue id in `error += ` fails the whole
        // build when the id is unknown, and nobody should lose an APK over that
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // THE TUNNEL. Pick exactly one and keep the version pinned; see docs/ANDROID-INTEGRATION.md §2
    // for the API the rest of the code expects from it (TunnelEngine in vpn/CoreApi.kt).
    // implementation("io.github.tahowang:tproxy:5.3.0")
    // implementation("io.github.nekohasemangroup:sing-box:1.10.0")
}
