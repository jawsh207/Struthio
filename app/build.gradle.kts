plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.struthio.nostr"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.struthio.nostr"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("STRUTHIO_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("STRUTHIO_VERSION_NAME") ?: "0.1.0"
    }

    val keystorePath = System.getenv("STRUTHIO_KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("STRUTHIO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STRUTHIO_KEY_ALIAS")
                keyPassword = System.getenv("STRUTHIO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to the debug key so CI test builds install without any secrets.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // One APK per CPU architecture: the native crypto library is the only per-ABI content.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
                "META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module", "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coroutines.android)
    implementation(libs.secp256k1.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.secp256k1.jvm)
}
