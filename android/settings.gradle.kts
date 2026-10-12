pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // the tProxy / sing-box AAR has to come from somewhere; a private mirror beats JitPack for a
        // self-updating app, because JitPack builds are re-run on demand and can change under you
        maven("https://jitpack.io")
    }
}

rootProject.name = "MeelanoVPN"
include(":app")
