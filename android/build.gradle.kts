// Root build file: nothing here but the plugin versions and one global quality gate.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// A 4-file APK build on a shared hosting team's laptop must not be slow: these two are the whole
// speed story for Compose + KAPT-free projects.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // no freeCompilerArgs here on purpose: `-P plugin:...` is the single most common cause of a
            // red build for a syntax reason, and Kotlin 2.0 already defaults liveLiterals=true.
            // Add plugin options in :app only, after the first green build.
        }
    }
}
