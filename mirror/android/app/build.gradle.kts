import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// mirror/airplay-go/build-aar.sh çalıştırılınca üretilir. Yoksa AirPlay kaynak seti
// derlenmez ve uygulama sadece UDP modunda çalışır.
val airplayAar = file("libs/airplay.aar")

// Gerçek AdMob kimlikleri ~/.gradle/gradle.properties veya komut satırı -P ile verilir;
// verilmezse Google'ın herkese açık TEST kimlikleri kullanılır (bunlar asla yayınlanmamalı).
fun prop(name: String, test: String) = (project.findProperty(name) as String?) ?: test
val admobAppId = prop("ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
val admobBannerId = prop("ADMOB_BANNER_ID", "ca-app-pub-3940256099942544/9214589741")
val admobInterstitialId = prop("ADMOB_INTERSTITIAL_ID", "ca-app-pub-3940256099942544/1033173712")
val admobRewardedId = prop("ADMOB_REWARDED_ID", "ca-app-pub-3940256099942544/5224354917")

// Yükleme anahtarı: keystore.properties (git'e girmez) -> storeFile, storePassword, keyAlias, keyPassword
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.screenmirror"
    compileSdk = 36

    defaultConfig {
        // Play'de yayınlandıktan sonra değiştirilemez.
        applicationId = "com.altinvolkan.imirror"
        minSdk = 30
        targetSdk = 36 // Play, 31 Ağustos 2026'dan beri yeni uygulamalarda API 36 hedefi ister
        // Her Play yüklemesinde versionCode'u artır (aynı kodla ikinci yükleme reddedilir)
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$admobInterstitialId\"")
        buildConfigField("String", "ADMOB_REWARDED_ID", "\"$admobRewardedId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    bundle {
        // Tüm diller AAB'de kalsın: Android 13+ uygulama başına dil seçimi de çalışsın
        language { enableSplit = false }
    }

    lint {
        // İlk yayın derlemesini lint uyarıları engellemesin; sorunlar Android Studio'da yine görünür
        checkReleaseBuilds = false
        abortOnError = false
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Play, yerel (Go) kitaplığın sembol tablosunu ister (çökme raporları için)
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets {
        if (airplayAar.exists()) {
            getByName("main").java.srcDir("src/airplay/java")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.android.billingclient:billing-ktx:7.1.1")
    implementation("com.google.android.gms:play-services-ads:23.6.0") // UMP onay SDK'sı dahildir
    if (airplayAar.exists()) {
        implementation(files(airplayAar))
    }
}

// Yayın derlemesinden önce güvenlik kontrolleri
tasks.configureEach {
    if (name == "bundleRelease" || name == "assembleRelease") {
        doFirst {
            check(keystoreProps.containsKey("storeFile")) {
                "keystore.properties yok: mirror\\android\\tools\\new-upload-key.ps1 betiğini çalıştır (docs/RELEASE.md)"
            }
            if (admobAppId.startsWith("ca-app-pub-3940256099942544")) {
                logger.warn("UYARI: AdMob TEST kimlikleri kullanılıyor. Gerçek kimlikleri ~/.gradle/gradle.properties içine yaz (docs/RELEASE.md).")
            }
            if (!airplayAar.exists()) {
                logger.warn("UYARI: app/libs/airplay.aar yok; AAB'de AirPlay çalışmaz (yalnızca UDP modu).")
            }
        }
    }
}
