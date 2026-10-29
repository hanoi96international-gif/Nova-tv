// Datenbank (Room) als eigenes Modul: Die Code-Erzeugung (KSP) muss so nur diese drei Dateien analysieren statt der
// ganzen App. Im App-Modul brauchte sie über 40 Minuten (das Übersetzen selbst nur Sekunden).
plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "de.ayutv.data.db"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Das Room-Plugin verwaltet die Schema-Dateien je Build-Variante.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val room = "2.8.5"
    api("androidx.room:room-runtime:$room")
    api("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
