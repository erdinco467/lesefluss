plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.erdinc.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.erdinc.reader"
        minSdk = 26
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
}

dependencies {
    // PDF text extraction (Apache 2.0). TXT and EPUB need no library.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
