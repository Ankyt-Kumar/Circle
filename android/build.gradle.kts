// AGP's built-in Kotlin and the Compose compiler use the same Kotlin version.
buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    id("com.google.gms.google-services") version "4.4.3" apply false
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
