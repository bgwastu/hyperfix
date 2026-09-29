val gitVersionCode = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }

val gitVersionName = providers.exec {
    commandLine("git", "describe", "--tags", "--always")
}.standardOutput.asText.map { it.trim() }

plugins {
    id("com.android.application")
}

android {
    namespace = "net.wastu.hyperfix"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.wastu.hyperfix"
        minSdk = 26
        targetSdk = 34
        versionCode = gitVersionCode.get()
        versionName = gitVersionName.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
