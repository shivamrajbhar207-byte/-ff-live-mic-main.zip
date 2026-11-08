plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fflivemic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fflivemic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.8.1-youtube-rtmp"
    }

    // Signing is optional: when the keystore environment variables are absent
    // (local builds, forks, pull requests) Gradle falls back to the default debug key.
    val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")
    val hasSigning = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

    signingConfigs {
        if (hasSigning) {
            create("stable") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasSigning) signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) signingConfig = signingConfigs.getByName("stable")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
