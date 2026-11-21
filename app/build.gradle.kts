import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val uploadSigning = listOf(
    "DRIVEVOICE_UPLOAD_STORE",
    "DRIVEVOICE_UPLOAD_STORE_PASSWORD",
    "DRIVEVOICE_UPLOAD_ALIAS",
    "DRIVEVOICE_UPLOAD_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }

require(uploadSigning.values.all { it == null } || uploadSigning.values.all { !it.isNullOrBlank() }) {
    "Set all four DRIVEVOICE_UPLOAD_* environment variables together."
}

android {
    namespace = "com.drivevoice.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.drivevoice.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    val upload = if (uploadSigning.values.all { !it.isNullOrBlank() }) {
        signingConfigs.create("upload") {
            storeFile = rootProject.file(checkNotNull(uploadSigning["DRIVEVOICE_UPLOAD_STORE"]))
            storePassword = uploadSigning["DRIVEVOICE_UPLOAD_STORE_PASSWORD"]
            keyAlias = uploadSigning["DRIVEVOICE_UPLOAD_ALIAS"]
            keyPassword = uploadSigning["DRIVEVOICE_UPLOAD_KEY_PASSWORD"]
        }
    } else null

    buildTypes.named("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = upload
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
