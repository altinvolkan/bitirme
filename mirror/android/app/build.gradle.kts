plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// mirror/airplay-go/build-aar.sh çalıştırılınca üretilir. Yoksa AirPlay kaynak seti
// derlenmez ve uygulama sadece UDP modunda çalışır.
val airplayAar = file("libs/airplay.aar")

android {
    namespace = "com.example.screenmirror"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screenmirror"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
    if (airplayAar.exists()) {
        implementation(files(airplayAar))
    }
}
