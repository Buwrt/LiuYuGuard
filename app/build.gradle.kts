plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.liuyuguard"
    compileSdk = libs.versions.compileSdk.get().toInt()

    /** 签名配置 */
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/release.jks")
            storePassword = "liuyuguard2024"
            keyAlias = "liuyuguard"
            keyPassword = "liuyuguard2024"
        }
    }

    defaultConfig {
        applicationId = "com.liuyuguard"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // JitPack repository for MPAndroidChart
    // (configured in settings.gradle.kts if needed)
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundle.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Activity & Navigation
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.bundle.lifecycle)

    // Charts
    implementation(libs.bundle.charts)

    // Shell / Root
    implementation(libs.bundle.litsu)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Coroutines
    implementation(libs.bundle.coroutines)

    // DataStore
    implementation(libs.datastore.preferences)

    // JSON
    implementation(libs.kotlinx.serialization.json)
}