import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Yükleme anahtarı: app modülüyle aynı keystore.properties (git'e girmez)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.altinvolkan.imirror.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.altinvolkan.imirror.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    bundle {
        language { enableSplit = false }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.configureEach {
    if (name == "bundleRelease" || name == "assembleRelease") {
        doFirst {
            check(keystoreProps.containsKey("storeFile")) {
                "keystore.properties yok: mirror\\android\\tools\\new-upload-key.ps1 betiğini çalıştır (docs/RELEASE.md)"
            }
        }
    }
}
