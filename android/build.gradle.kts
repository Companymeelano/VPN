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
            freeCompilerArgs.addAll(
                // live literals: string/number constants stop being read from a class file on every
                // recomposition - small, free, and it is the cheapest frame-time win in a Compose app
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:liveLiterals=true",
                // no compose time markers in a release build: they cost a trace write per recomposition
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:traceMarkersEnabled=false",
            )
        }
    }
}
