plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.anbur.applock"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.anbur.applock"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Stały klucz -> kolejne wersje instalują się jako aktualizacja (bez odinstalowywania)
    signingConfigs {
        create("fixed") {
            storeFile = file("applock.jks")
            storePassword = "applock123"
            keyAlias = "applock"
            keyPassword = "applock123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
