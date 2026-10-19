plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "de.ayutv"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.ayutv"
        // Fire OS 6 entspricht API 25: alle Fire TV Sticks ab 2018. ONNX Runtime verlangt API 24.
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "0.13.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Fire TV: ARM (32/64 Bit). x86_64 nur für den Emulator-Test in der CI.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        // App-Schlüssel für OpenSubtitles (GitHub-Secret OPENSUBTITLES_API_KEY). Damit funktionieren
        // Online-Untertitel auf jedem Gerät ohne Einrichtung; ein eigener Schlüssel hat Vorrang.
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"${System.getenv("OPENSUBTITLES_API_KEY").orEmpty().trim()}\"")
    }

    // Fester Schlüssel für alle Builds (Debug, Release, CI): Jede neue Version lässt sich über die
    // alte installieren, Playlists und Einstellungen bleiben erhalten. Zum Sideloaden gedacht;
    // für einen Store einen eigenen, geheimen Schlüssel verwenden.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/ayutv.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Native Bibliotheken komprimiert ablegen: hält die APK klein (ONNX Runtime, FFmpeg).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Release-Builds nicht an Lint-Hinweisen scheitern lassen.
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Das Room-Plugin verwaltet die Schema-Dateien je Build-Variante. Mit dem alten ksp-Argument
// schrieben Debug- und Release-Build parallel in dieselbe Datei und zerstörten sie gegenseitig.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val compose = "1.12.1"
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.ui:ui:$compose")
    // Die Material-Icons werden seit 1.7.8 nicht mehr weiterentwickelt.
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Kindersicherung: sperren, sobald die App in den Hintergrund geht
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    // Playlists und TV-Guide im Hintergrund aktuell halten
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Kompiliert beim Sideloaden die mitgelieferten Baseline-Profile von Compose: flüssigere UI ab dem ersten Start.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // FFmpeg-Tondecoder (in der CI aus dem Media3-Quellcode gebaut, siehe ci/build-ffmpeg.sh).
    // Ohne die Datei baut die App trotzdem, dann nur mit den Decodern des Geräts.
    implementation("androidx.media3:media3-decoder:$media3")
    val ffmpegAar = file("libs/lib-decoder-ffmpeg-release.aar")
    if (ffmpegAar.exists()) implementation(files(ffmpegAar))

    val room = "2.8.5"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    // Untertitel-Übersetzung auf dem Gerät: kostenlos, ohne Konto, Sprachpakete werden einmalig geladen.
    // QR-Code für die Einrichtung per Handy
    implementation("com.google.zxing:core:3.5.4")
    // Übersetzung auf dem Gerät (OPUS-MT als ONNX)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val coil = "3.6.3"
    implementation("io.coil-kt.coil3:coil-compose:$coil")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coil")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
