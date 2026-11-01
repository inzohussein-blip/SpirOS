plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.spiros.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spiros.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    // Two editions from one codebase:
    //  - secure: the main app, modern targetSdk with every Android hardening.
    //  - speed:  "SpirOS Linux", adds the fast Linux desktop (proot). Running Linux
    //    programs from app storage is only allowed for apps targeting Android 9
    //    (API 28) or lower, like Termux, so this edition targets 28. It installs
    //    side by side with the secure edition and keeps its own data.
    flavorDimensions += "edition"
    productFlavors {
        create("secure") {
            dimension = "edition"
            isDefault = true
        }
        create("speed") {
            dimension = "edition"
            applicationIdSuffix = ".speed"
            versionNameSuffix = "-linux"
            targetSdk = 28
        }
    }

    signingConfigs {
        // Fixed debug key so CI builds update in place instead of forcing an
        // uninstall (which would wipe the vault). Never used for releases.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    // One APK per ABI: phones get only arm64 (QEMU is ~40 MB per ABI); the x86_64 APK
    // exists for the Lab self-test on the CI emulator.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    lint {
        // CI runs lintDebug only to catch calls to APIs newer than minSdk.
        checkOnly += "NewApi"
        abortOnError = true
    }
    packaging {
        jniLibs {
            // Extract native libs to nativeLibraryDir: the bundled QEMU executable must
            // exist as a real file there to be executed.
            useLegacyPackaging = true
            // QEMU and its libraries arrive already stripped from Termux; don't let the
            // build rewrite them again.
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.tink.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
