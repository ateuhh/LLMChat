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
        kotlinCompilerExtensionVersion = "2.0.21"
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
    implementation (libs.androidx.ui)
    implementation (libs.androidx.ui.tooling.preview)
    debugImplementation (libs.androidx.ui.tooling)
    implementation( libs.androidx.material3)
    implementation (libs.androidx.activity.compose)
    implementation( libs.androidx.lifecycle.runtime.ktx)
    implementation (libs.androidx.lifecycle.viewmodel.compose)

    // Networking
    implementation (libs.okhttp)
    implementation (libs.retrofit)
    implementation (libs.converter.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.logging.interceptor) // Используйте последнюю версию


    // Coroutines / Flow
    implementation (libs.kotlinx.coroutines.android)

    // DataStore
    implementation (libs.androidx.datastore.preferences)

    // Icons
    implementation(libs.material)
    implementation (libs.androidx.material.icons.extended)

    implementation(libs.coil.compose)
}
