plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.pdfvoz"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.pdfvoz"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
