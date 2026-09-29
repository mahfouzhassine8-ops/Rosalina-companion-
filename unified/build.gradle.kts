plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val emulatorQa = providers.gradleProperty("unifiedEmulatorQa").orNull == "true"
android {
    namespace = "com.rosalina.unified"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rosalina.unified"
        minSdk = 30
        targetSdk = 35
        versionCode = providers.gradleProperty("unifiedVersionCode").orNull?.toInt() ?: 10012
        versionName = "1.0-unified-pristine-audit-c1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += if (emulatorQa) "x86_64" else "arm64-v8a" }
    }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/librosalina-*.so" } }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    if (!emulatorQa) implementation(project(":lib"))
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
android.sourceSets.getByName("main").java.srcDir(if (emulatorQa) "src/emulator/java" else "src/device/java")
