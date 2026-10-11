plugins {
    id("com.android.application")
}
android {
    namespace = "dev.local.aibrowser"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "dev.local.aibrowser"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.9.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    // Match the exact path restored by CI instead of an SDK/AGP-dependent default.
    signingConfigs.getByName("debug") {
        storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("org.mozilla.geckoview:geckoview:157.0.20261005135250")
    implementation("androidx.core:core-ktx:1.19.0")
    testImplementation("junit:junit:4.13.2")
}
