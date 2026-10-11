import java.security.KeyStore
import java.security.MessageDigest

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
        versionCode = 19
        versionName = "0.10.0"
        manifestPlaceholders["appLabel"] = "Abwr"
    }
    val distributionKey = signingConfigs.create("distribution") {
        storeFile = file(System.getenv("ABWR_KEYSTORE_PATH") ?: (System.getProperty("user.home") + "/.android/debug.keystore"))
        storePassword = System.getenv("ABWR_STORE_PASSWORD") ?: "android"
        keyAlias = System.getenv("ABWR_KEY_ALIAS") ?: "androiddebugkey"
        keyPassword = System.getenv("ABWR_KEY_PASSWORD") ?: storePassword
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = distributionKey }
        debug { applicationIdSuffix = ".dev"; manifestPlaceholders["appLabel"] = "Abwr Dev" }
    }
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

val verifyDistributionSigning = tasks.register("verifyDistributionSigning") {
    doLast {
        val signing = android.signingConfigs.getByName("distribution")
        val keyFile = requireNotNull(signing.storeFile)
        check(keyFile.isFile) { "Signing key missing. Restore ABWR_SIGNING_KEY_B64; do not generate a new key." }
        val keyStore = KeyStore.getInstance("JKS")
        keyFile.inputStream().use { keyStore.load(it, requireNotNull(signing.storePassword).toCharArray()) }
        val certificate = requireNotNull(keyStore.getCertificate(signing.keyAlias)) { "Signing alias missing" }
        val sha = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expected = rootProject.file("signing/certificate-sha256.txt").readText().trim()
        check(sha == expected) { "Signing certificate changed. Refusing an APK that would break installed updates." }
        println("Distribution signing certificate verified: $sha")
    }
}
tasks.matching { it.name == "validateSigningRelease" }.configureEach { dependsOn(verifyDistributionSigning) }
dependencies {
    implementation("org.mozilla.geckoview:geckoview:157.0.20261005135250")
    implementation("androidx.core:core-ktx:1.19.0")
    testImplementation("junit:junit:4.13.2")
}
