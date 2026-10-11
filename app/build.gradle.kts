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
        versionCode = 9
        versionName = "0.4.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("org.mozilla.geckoview:geckoview:157.0.20261005135250")
    implementation("androidx.core:core-ktx:1.19.0")
}
