import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val circleAuthMode = providers.gradleProperty("circleAuthMode").orElse("demo").get()
require(circleAuthMode in listOf("demo", "emulator", "firebase")) { "circleAuthMode must be demo, emulator or firebase" }
if (circleAuthMode == "firebase") {
    require(file("google-services.json").isFile) { "Add app/google-services.json from Firebase; see docs/AUTH_SETUP.md" }
    apply(plugin = "com.google.gms.google-services")
}
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val circleApiUrl = providers.gradleProperty("circleApiBaseUrl").orElse("http://10.0.2.2:8080").get()
val circleAuthHost = providers.gradleProperty("circleAuthEmulatorHost").orElse("10.0.2.2").get()
require(circleAuthHost in listOf("10.0.2.2", "127.0.0.1", "localhost")) { "Use a local authentication emulator" }

android {
    namespace = "com.circle.app"
    compileSdk = 37
    defaultConfig {
        manifestPlaceholders["circleMapsApiKey"] = providers.gradleProperty("circleMapsApiKey").orElse("").get()
        applicationId = "com.circle.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        // Android Emulator routes this address to the development computer.
        buildConfigField("String", "API_BASE_URL", quoted(circleApiUrl))
        buildConfigField("String", "AUTH_MODE", quoted(circleAuthMode))
        buildConfigField("String", "AUTH_EMULATOR_HOST", quoted(circleAuthHost))
    }
    buildTypes {
        debug { applicationIdSuffix = ".demo" }
        release {
            // No public production endpoint exists in this milestone.
            buildConfigField("String", "API_BASE_URL", "\"https://invalid.invalid\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

// JDK 25 runs Gradle. Android bytecode remains Java 17 compatible.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = circleAuthMode == "firebase" }
}

dependencies {
    implementation(libs.firebase.auth)
    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // noinspection UseTomlInstead
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // splash screen
    implementation("androidx.core:core-splashscreen:1.2.0")
}
