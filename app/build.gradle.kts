plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.anton.touchlock"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.anton.touchlock"
        minSdk = 26
        targetSdk = 35
        // Номер сборки на GitHub = номер версии, чтобы новые версии ставились поверх старых
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"
    }

    // Постоянный ключ подписи: обновления ставятся поверх без удаления приложения
    signingConfigs {
        create("shared") {
            storeFile = file("touchlock.keystore")
            storePassword = "android"
            keyAlias = "touchlock"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
