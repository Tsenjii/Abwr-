buildscript {
    dependencies {
        // GeckoView / AndroidX 2026 artifacts use Kotlin metadata 2.4.0.
        // AGP 9 built-in Kotlin uses this newer runtime compiler when present.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
}
