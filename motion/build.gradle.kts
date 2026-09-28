plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// Emulator-only tests contain no inference worker; shipped APK always uses ARM64.
val codecTest = providers.gradleProperty("motionCodecTest").orNull == "true"
val motionSigningStore = providers.gradleProperty("motionSigningStore").orNull
val motionSigningStorePassword = providers.gradleProperty("motionSigningStorePassword").orNull
val motionSigningKeyAlias = providers.gradleProperty("motionSigningKeyAlias").orNull
val motionSigningKeyPassword = providers.gradleProperty("motionSigningKeyPassword").orNull

android {
    namespace = "com.rosalina.motion"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rosalina.motionlab"
        minSdk = 30
        targetSdk = 35
        versionCode = 30002
        versionName = "3.0.1-motion-lab-rc2"
        ndk { abiFilters += if(codecTest) "x86_64" else "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (motionSigningStore != null) {
        signingConfigs {
            create("motionPersistent") {
                storeFile = file(motionSigningStore)
                storePassword = motionSigningStorePassword
                keyAlias = motionSigningKeyAlias
                keyPassword = motionSigningKeyPassword
            }
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("motionPersistent")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/librosalina-motion.so" } }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
