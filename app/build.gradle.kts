plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tr.ogur.kamerabekcisi"
    compileSdk = 35

    defaultConfig {
        applicationId = "tr.ogur.kamerabekcisi"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("kb") {
            storeFile = file("kamerabekcisi.jks")
            storePassword = "kamera123"
            keyAlias = "kb"
            keyPassword = "kamera123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kb")
        }
        debug {
            signingConfig = signingConfigs.getByName("kb")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/native-image/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
                "META-INF/*.kotlin_module"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += "onnx"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // RTSP görüntü alma (FFmpeg)
    implementation("org.bytedeco:javacv:1.5.10")
    implementation("org.bytedeco:javacpp:1.5.10")
    implementation("org.bytedeco:javacpp:1.5.10:android-arm64")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:android-arm64")

    // Yapay zeka (YOLO, ONNX Runtime)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
