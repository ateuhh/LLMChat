import org.gradle.kotlin.dsl.debugImplementation
import org.gradle.kotlin.dsl.implementation

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("plugin.serialization") version "2.2.0"
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.llmchat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.llmchat"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Compose
    implementation (platform ("androidx.compose:compose-bom:2024.10.00"))
    implementation ("androidx.compose.ui:ui")
    implementation ("androidx.compose.ui:ui-tooling-preview")
    debugImplementation ("androidx.compose.ui:ui-tooling")
    implementation( "androidx.compose.material3:material3:1.3.2")
    implementation ("androidx.activity:activity-compose:1.9.3")
    implementation( "androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation ("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")

    // Networking
    implementation ("com.squareup.okhttp3:okhttp:4.12.0")
    implementation ("com.squareup.retrofit2:retrofit:2.11.0")
    implementation ("com.squareup.retrofit2:converter-moshi:3.0.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")

    // Coroutines / Flow
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore
    implementation ("androidx.datastore:datastore-preferences:1.1.1")

    // Icons
    implementation("com.google.android.material:material:1.12.0")
    implementation ("androidx.compose.material:material-icons-extended:1.7.5")
}
