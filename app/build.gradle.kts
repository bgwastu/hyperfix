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
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
