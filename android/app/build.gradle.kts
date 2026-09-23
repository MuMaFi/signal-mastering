plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Renders the Compose screens to PNG on the JVM (layoutlib), so the Android UI is
    // checked by eye the same way the web preview is — no emulator needed.
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "app.signal.isolate"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.signal.isolate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // ONNX Runtime ships arm64-v8a / armeabi-v7a / x86 / x86_64.
        // Separation is only realistic on 64-bit ARM (and x86_64 for emulators),
        // so we drop the 32-bit slices instead of shipping an APK that OOMs.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Debug-signed by default so `assembleRelease` produces an installable
            // APK in CI. Replace with a real keystore before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // ONNX Runtime's native library is ~33 MB per ABI; shipping one APK per ABI keeps
    // the download a user actually installs close to half the size of the universal one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Store native libraries compressed. By default AGP keeps them uncompressed so
        // they can be mapped straight from the APK, but ONNX Runtime alone is 33 MB raw
        // and 12 MB deflated: this cuts the arm64 download from 35 MB to about 15 MB.
        // The cost is a one-off extraction on install, trivial next to the model weights.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
