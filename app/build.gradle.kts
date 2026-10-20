plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.amitjha.kosh"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amitjha.kosh"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "2.4.2"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/kosh-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = providers.gradleProperty("KOSH_STORE_PASSWORD").orNull
                keyAlias = "kosh"
                keyPassword = providers.gradleProperty("KOSH_KEY_PASSWORD").orNull
            }
        }
    }

    // Two editions from one codebase: "standard" (may fetch public NPS prices) and "offline", which has no
    // INTERNET permission at all, so Android itself guarantees nothing can leave the phone.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            buildConfigField("boolean", "NETWORK_ENABLED", "true")
        }
        create("offline") {
            dimension = "edition"
            applicationId = "com.amitjha.kosh.offline"
            buildConfigField("boolean", "NETWORK_ENABLED", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the Kosh key when it is present locally. F-Droid builds without it and, because the build
            // is reproducible, publishes the APK from the GitHub release with its Kosh signature.
            if (rootProject.file("keystore/kosh-release.jks").exists()) signingConfig = signingConfigs.getByName("release")
        }
        // Debug builds use the standard debug key, so a debuggable APK can never install over the real app.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Native libraries are packaged exactly as the libraries ship them. Stripping depends on which NDK the build
        // machine has, which would make F-Droid's rebuild differ from the release APK (reproducible builds).
        jniLibs.keepDebugSymbols += "**/*.so"
    }
    // No "dependency metadata" in the APK signing block: it is encrypted for Google Play only, and F-Droid's scanner
    // rejects APKs that carry it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:sms"))
    implementation(project(":core:finance"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)
    // QR codes for household pairing (Apache-2.0)
    implementation(libs.zxing.android.embedded)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
